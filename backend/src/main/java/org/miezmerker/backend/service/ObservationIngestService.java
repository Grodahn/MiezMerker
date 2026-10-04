package org.miezmerker.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.NodeState;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.security.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Batch ingest for immutable raw observations (#9).
 *
 * <p>Idempotency key is {@code (node_id, sequence)} with a database unique
 * constraint as the backstop, not only an application check:
 * <ul>
 *   <li>first occurrence is inserted,</li>
 *   <li>identical retry is accepted idempotently,</li>
 *   <li>same key with conflicting payload is rejected and logged, never
 *       silently overwritten,</li>
 *   <li>partial/retried batches stay safe and concurrent duplicates stay safe.</li>
 * </ul>
 *
 * <p>Unknown-node policy: nodes must be claimed via #18 before they can deliver
 * data; ingest never auto-creates nodes. Items for unknown nodes are rejected
 * per item, not silently dropped.
 *
 * <p>Clock/deployment caveat: observations with {@code UNKNOWN} clock carry no
 * wall-clock value and are stored without deployment attribution (NULL) rather
 * than guessing the current site. Ingest never fabricates a timestamp-based
 * answer; sequence/incarnation/monotonic metadata is retained so later
 * sequence-based deployment boundaries remain possible (#10).
 */
@Service
public class ObservationIngestService {
    private static final Logger log = LoggerFactory.getLogger(ObservationIngestService.class);

    static final Set<String> CLOCK_STATUSES = Set.of("SYNCED", "RTC_ONLY", "UNKNOWN", "KNOWN");

    public record IngestItem(UUID nodeId, long sequence, String chipId, Long observedAtMs,
            String clockStatus, String incarnation, Long monotonicMs, Integer bootCounter) {}

    public record ItemResult(UUID nodeId, long sequence, String status, String message) {}

    public record BatchResult(int inserted, int duplicates, int conflicts, int rejected,
            List<ItemResult> results) {}

    private final TenantService tenants;
    private final OrganizationRepository organizations;
    private final NodeRepository nodes;
    private final RawObservationRepository observations;
    private final NodeDeploymentRepository deployments;
    private final ObjectProvider<ObservationIngestService> self;

    public ObservationIngestService(TenantService tenants,
            OrganizationRepository organizations, NodeRepository nodes,
            RawObservationRepository observations, NodeDeploymentRepository deployments,
            ObjectProvider<ObservationIngestService> self) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.nodes = nodes;
        this.observations = observations;
        this.deployments = deployments;
        this.self = self;
    }

    /** Orchestrator: membership is checked once; every item commits independently. */
    @Transactional
    public BatchResult ingestBatch(UUID userId, UUID organizationId, List<IngestItem> items) {
        tenants.requireActive(userId, organizationId);
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int inserted = 0;
        int duplicates = 0;
        int conflicts = 0;
        int rejected = 0;
        List<ItemResult> results = new ArrayList<>();
        for (IngestItem item : items) {
            ItemResult result;
            try {
                result = self.getObject().ingestOne(org.getId(), item);
            } catch (DataIntegrityViolationException concurrent) {
                // Concurrent duplicate won the unique constraint race between our
                // existence check and our insert; classify against the winner.
                result = self.getObject().classifyExisting(item);
                if (result == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "duplicate observation conflicts with a concurrent upload");
                }
            } catch (ResponseStatusException e) {
                if (e.getStatusCode() != HttpStatus.FORBIDDEN) {
                    throw e;
                }
                // Per-item tenant violation (e.g. a node of another organization
                // inside an otherwise valid batch): report it on the item without
                // failing sibling items that already committed independently.
                result = new ItemResult(item.nodeId(), item.sequence(), "FORBIDDEN",
                        e.getReason() != null ? e.getReason() : "forbidden");
            }
            results.add(result);
            switch (result.status()) {
                case "CREATED" -> inserted++;
                case "DUPLICATE_IDENTICAL" -> duplicates++;
                case "CONFLICT" -> conflicts++;
                default -> rejected++;
            }
        }
        return new BatchResult(inserted, duplicates, conflicts, rejected, results);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult ingestOne(UUID organizationId, IngestItem item) {
        String problem = validateItem(item);
        if (problem != null) {
            return new ItemResult(item.nodeId(), item.sequence(), "INVALID", problem);
        }
        String chip = Cat.normalizeChipId(item.chipId());
        String clock = RawObservation.normalizeClockStatus(item.clockStatus());

        NodeDevice node = nodes.findByIdLocked(item.nodeId()).orElse(null);
        if (node == null) {
            return new ItemResult(item.nodeId(), item.sequence(), "UNKNOWN_NODE",
                    "node is not registered; claim it via #18 before uploading");
        }
        if (node.getState() != NodeState.CLAIMED || node.getOrganization() == null
                || !node.getOrganization().getId().equals(organizationId)) {
            // Same visibility rule as the node detail endpoint: foreign nodes are
            // forbidden, never silently accepted into another tenant.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "node does not belong to this organization");
        }

        RawObservation existing =
                observations.findByNodeNodeIdAndSequence(item.nodeId(), item.sequence())
                        .orElse(null);
        if (existing != null) {
            if (existing.samePayload(chip, item.observedAtMs(), clock, item.incarnation(),
                    item.monotonicMs(), item.bootCounter())) {
                node.touchContact();
                nodes.save(node);
                return new ItemResult(item.nodeId(), item.sequence(), "DUPLICATE_IDENTICAL",
                        "identical retry accepted idempotently");
            }
            // Never overwrite: keep the first payload, make the conflict visible.
            // Logs carry technical identity only, no chip/location duplication.
            log.warn("observation conflict node={} sequence={} org={}: "
                    + "retry with differing payload rejected",
                    item.nodeId(), item.sequence(), organizationId);
            return new ItemResult(item.nodeId(), item.sequence(), "CONFLICT",
                    "sequence already stored with a different payload; original kept");
        }

        var resolution = resolveDeployment(item.nodeId(), item.observedAtMs(), clock);
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        RawObservation row = new RawObservation(org, node, item.sequence(), chip,
                item.observedAtMs(), clock, item.incarnation(), item.monotonicMs(),
                item.bootCounter(), resolution.site(), resolution.deployment());
        observations.saveAndFlush(row);
        node.touchContact();
        nodes.save(node);
        return new ItemResult(item.nodeId(), item.sequence(), "CREATED", "stored");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemResult classifyExisting(IngestItem item) {
        var existing = observations.findByNodeNodeIdAndSequence(item.nodeId(), item.sequence())
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if (existing.samePayload(Cat.normalizeChipId(item.chipId()), item.observedAtMs(),
                RawObservation.normalizeClockStatus(item.clockStatus()), item.incarnation(),
                item.monotonicMs(), item.bootCounter())) {
            return new ItemResult(item.nodeId(), item.sequence(), "DUPLICATE_IDENTICAL",
                    "identical retry accepted idempotently");
        }
        log.warn("observation conflict node={} sequence={}: "
                + "concurrent upload with differing payload rejected",
                item.nodeId(), item.sequence());
        return new ItemResult(item.nodeId(), item.sequence(), "CONFLICT",
                "sequence already stored with a different payload; original kept");
    }

    private record Resolution(org.miezmerker.backend.domain.FeedingSite site,
            org.miezmerker.backend.domain.NodeDeployment deployment) {}

    private Resolution resolveDeployment(UUID nodeId, Long observedAtMs, String clock) {
        if ("UNKNOWN".equals(clock) || observedAtMs == null) {
            // No trustworthy wall-clock value: leave attribution unresolved
            // instead of guessing the node's current site.
            return new Resolution(null, null);
        }
        Instant at = Instant.ofEpochMilli(observedAtMs);
        var covering = deployments.findCovering(nodeId, at);
        if (covering.size() != 1) {
            if (covering.size() > 1) {
                log.warn("node {} has {} overlapping deployments at {}; "
                        + "observation left unattributed", nodeId, covering.size(), at);
            }
            return new Resolution(null, null);
        }
        var deployment = covering.get(0);
        return new Resolution(deployment.getFeedingSite(), deployment);
    }

    static String validateItem(IngestItem item) {
        if (item == null) {
            return "item is null";
        }
        if (item.nodeId() == null) {
            return "nodeId is required";
        }
        if (item.sequence() < 1) {
            // 0 is reserved as invalid by the firmware core; first sequence is 1.
            return "sequence must be >= 1";
        }
        if (item.chipId() == null || item.chipId().trim().isEmpty()
                || item.chipId().trim().length() > 64) {
            return "chipId must have 1..64 characters";
        }
        if (item.clockStatus() == null
                || !CLOCK_STATUSES.contains(
                        item.clockStatus().trim().toUpperCase(Locale.ROOT))) {
            return "clockStatus must be one of SYNCED, RTC_ONLY, UNKNOWN (KNOWN accepted as wire alias)";
        }
        String clock = item.clockStatus().trim().toUpperCase(Locale.ROOT);
        if ("UNKNOWN".equals(clock) && item.observedAtMs() != null) {
            return "observedAtMs must be absent when clockStatus is UNKNOWN";
        }
        if (!"UNKNOWN".equals(clock) && item.observedAtMs() == null) {
            return "observedAtMs is required unless clockStatus is UNKNOWN";
        }
        if (item.observedAtMs() != null && item.observedAtMs() < 0) {
            return "observedAtMs must be >= 0";
        }
        if (item.monotonicMs() != null && item.monotonicMs() < 0) {
            return "monotonicMs must be >= 0";
        }
        if (item.bootCounter() != null && item.bootCounter() < 0) {
            return "bootCounter must be >= 0";
        }
        if (item.incarnation() != null && !item.incarnation().isBlank()) {
            try {
                UUID.fromString(item.incarnation());
            } catch (IllegalArgumentException e) {
                return "incarnation must be a UUID string";
            }
        }
        return null;
    }
}
