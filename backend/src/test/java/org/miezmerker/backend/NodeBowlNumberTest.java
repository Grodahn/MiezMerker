package org.miezmerker.backend;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.repo.NodeRepository;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("node")
@Tag("schema")
class NodeBowlNumberTest {
    @Autowired NodeRepository nodes;
    @Autowired DataSource dataSource;
    @Autowired org.miezmerker.backend.crypto.CredentialIssuerService issuer;

    @Test
    void optionalNameCannotOverflowAnOtherwiseUsableClaimReceipt() throws Exception {
        var node = UUID.randomUUID();
        var org = UUID.randomUUID();
        String name = "\u732b".repeat(255);
        String x = issuer.publicXB64u(), y = issuer.publicYB64u();
        String fingerprint = EcKeyUtils.fingerprintOfXY(x, y);
        for (int length = 0; length <= 500; length += 5) {
            String contact = "\u732b".repeat(length);
            String legacy = issuer.issueClaimReceipt(node, org, "org", name, contact,
                    x, y, fingerprint, java.time.Instant.now());
            if (legacy.length() < 3700 || legacy.length() > 4000) continue;
            String numbered = issuer.issueClaimReceipt(node, org, "org", name, contact,
                    x, y, fingerprint, java.time.Instant.now(), 4711, "\u732b".repeat(100));
            assertTrue(numbered.length() <= 4096);
            var claims = issuer.verify(numbered);
            assertEquals(4711L, claims.getLongClaim("bowl_number"));
            assertNull(claims.getStringClaim("display_name"));
            assertEquals(node.toString(), claims.getSubject());
            return;
        }
        fail("No receipt near the legacy byte budget was exercised");
    }

    NodeDevice create() {
        UUID id = UUID.randomUUID();
        return nodes.saveAndFlush(new NodeDevice(id, "x", "y", id.toString(), "test"));
    }

    @Test
    void concurrentCreationUsesDatabaseAllocationAndKeepsUuid() throws Exception {
        var numbers = new HashSet<Integer>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<NodeDevice>>();
            for (int i = 0; i < 32; ++i) futures.add(executor.submit(this::create));
            for (var future : futures) {
                var node = future.get();
                assertTrue(node.getBowlNumber() > 0);
                assertTrue(numbers.add(node.getBowlNumber()));
                var stored = nodes.findById(node.getNodeId()).orElseThrow();
                assertEquals(node.getNodeId(), stored.getNodeId());
                assertEquals(node.getBowlNumber(), stored.getBowlNumber());
                stored.setDisplayName("renamed");
                assertEquals(node.getBowlNumber(), nodes.saveAndFlush(stored).getBowlNumber());
            }
        }
        var deleted = create();
        nodes.deleteById(deleted.getNodeId());
        assertTrue(create().getBowlNumber() > deleted.getBowlNumber());
    }

    @Test
    void migrationBackfillsDeterministicallyAndAdvancesSequence() throws Exception {
        String schema = "bowl_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
        config.target("20").load().migrate();
        var first = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var second = UUID.fromString("22222222-2222-4222-8222-222222222222");
        try (var c = dataSource.getConnection()) {
            c.setSchema(schema);
            // Insert in reverse order, equal timestamps: UUID breaks the tie.
            for (var id : new UUID[]{second, first}) {
                try (var insert = c.prepareStatement("INSERT INTO nodes (node_id, public_key_x, public_key_y, fingerprint, state, created_at) VALUES (?, 'x', 'y', ?, 'UNCLAIMED', '2026-01-01 00:00:00+00')")) {
                    insert.setObject(1, id); insert.setString(2, id.toString()); insert.executeUpdate();
                }
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT node_id, bowl_number FROM nodes ORDER BY bowl_number")) {
                assertTrue(r.next()); assertEquals(first, r.getObject(1, UUID.class)); assertEquals(1, r.getInt(2));
                assertTrue(r.next()); assertEquals(second, r.getObject(1, UUID.class)); assertEquals(2, r.getInt(2));
                assertFalse(r.next());
            }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT nextval('node_bowl_number_seq')")) {
                assertTrue(r.next()); assertEquals(3, r.getInt(1));
            }
            try (var s = c.createStatement()) {
                assertThrows(java.sql.SQLException.class, () -> s.executeUpdate("UPDATE nodes SET bowl_number = 0"));
                assertThrows(java.sql.SQLException.class, () -> s.executeUpdate("UPDATE nodes SET bowl_number = 1 WHERE bowl_number = 2"));
            }
        } finally {
            // INFORMATION_SCHEMA is shared with other regression tests.
            try (var c = dataSource.getConnection(); var statement = c.createStatement()) {
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }
}
