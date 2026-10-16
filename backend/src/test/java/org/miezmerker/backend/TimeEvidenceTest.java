package org.miezmerker.backend;

import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.miezmerker.backend.crypto.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.service.*;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.miezmerker.backend.service.TemporalProjectionEngine.Quality.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"spring.datasource.hikari.maximum-pool-size=2","spring.datasource.hikari.minimum-idle=1"})
@ActiveProfiles("test") @Tag("node") @Tag("auth") @Tag("visits")
// This dedicated signer/uncertainty context cannot be reused by other classes.
// Release its pool after the class instead of consuming CI PostgreSQL clients.
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TimeEvidenceTest {
    static final KeyPair SIGNER=EcKeyUtils.generateP256();
    static final long UTC=1791748800123L;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r) {
        r.add("miezmerker.issuer.private-key-pkcs8-b64",()->EcKeyUtils.pkcs8B64(SIGNER.getPrivate()));
        r.add("miezmerker.issuer.public-key-spki-b64",()->Base64.getEncoder().encodeToString(SIGNER.getPublic().getEncoded()));
        r.add("miezmerker.time-projection.server-error-ms",()->100);
        r.add("miezmerker.time-projection.sampling-ms",()->1);
        r.add("miezmerker.time-projection.drift-ppm",()->20);
        r.add("miezmerker.time-projection.provenance",()->"deterministic-test-budget-v1");
    }
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired OrganizationRepository orgs;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository raws;
    @Autowired CatRepository cats;
    @Autowired BootEpochRepository boots;
    @Autowired TimeAnchorRepository anchors;
    @Autowired TimeEvidenceService evidence;
    @Autowired TemporalProjectionService projections;
    @Autowired TemporalVisitService temporalVisits;
    @Autowired ObservationIngestService ingest;
    @Autowired CredentialIssuerService issuer;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired FeedingSiteRepository sites;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TimeUncertaintyPolicy uncertainty;
    @Value("${local.server.port}") int port;
    final ObjectMapper mapper=new ObjectMapper();
    Organization orgA,orgB; AppUser userA,userB;
    KeyPair key; NodeDevice node; UUID incarnation;
    HttpClient client;
    @BeforeEach void setup() {
        cleaner.clean();
        orgA=orgs.save(new Organization("a","A",null)); orgB=orgs.save(new Organization("b","B",null));
        userA=users.save(new AppUser("a@example.org",passwords.encode("test-password")));
        userB=users.save(new AppUser("b@example.org",passwords.encode("test-password")));
        memberships.save(new OrganizationMembership(orgA,userA,MembershipRole.ADMIN,MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB,userB,MembershipRole.ADMIN,MembershipStatus.ACTIVE));
        key=EcKeyUtils.generateP256(); var pub=(ECPublicKey)key.getPublic();
        node=new NodeDevice(UUID.randomUUID(),EcKeyUtils.xOf(pub),EcKeyUtils.yOf(pub),EcKeyUtils.fingerprintOf(pub),"test");
        node.claim(orgA); node=nodes.save(node);
        jdbc.update("UPDATE nodes SET claimed_at=? WHERE node_id=?",java.sql.Timestamp.from(Instant.ofEpochMilli(UTC-10000000)),node.getNodeId());
        incarnation=UUID.randomUUID();
        client=HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
    }
    TimeEvidenceProtocol.Request request(long boot,long challenge,long accept,long utc) {
        var pub=(ECPublicKey)key.getPublic();
        byte[] raw=new byte[65]; raw[0]=4;
        System.arraycopy(EcKeyUtils.b64uDecode(EcKeyUtils.xOf(pub)),0,raw,1,32);
        System.arraycopy(EcKeyUtils.b64uDecode(EcKeyUtils.yOf(pub)),0,raw,33,32);
        byte[] nonce=new byte[32]; new SecureRandom().nextBytes(nonce);
        var c=new NodeTimeProtocol.Challenge(node.getNodeId().toString(),incarnation.toString(),boot,EcKeyUtils.b64u(nonce),
            BigInteger.valueOf(challenge),EcKeyUtils.b64u(raw),EcKeyUtils.fingerprintOf(pub),EcKeyUtils.b64u(new byte[64]),1);
        c=new NodeTimeProtocol.Challenge(c.nodeId(),c.incarnation(),boot,c.challengeNonce(),c.challengeUptimeMs(),c.devicePublicKey(),
            c.devicePublicKeyFingerprint(),EcKeyUtils.b64u(EcKeyUtils.signRaw(key.getPrivate(),NodeTimeProtocol.challengeBytes(c))),1);
        String proof=issuer.issueTimeProof(c,utc);
        var a=new NodeTimeProtocol.Acceptance(c.nodeId(),c.incarnation(),boot,EcKeyUtils.b64u(NodeTimeProtocol.digest(proof.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            EcKeyUtils.b64u(NodeTimeProtocol.digest(nonce)),BigInteger.valueOf(accept),UUID.randomUUID().toString(),EcKeyUtils.b64u(new byte[64]),1);
        a=new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),boot,a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),
            EcKeyUtils.b64u(EcKeyUtils.signRaw(key.getPrivate(),NodeTimeProtocol.acceptanceBytes(c,proof,a))),1);
        return new TimeEvidenceProtocol.Request(orgA.getId().toString(),new TimeEvidenceProtocol.Boot(c.nodeId(),c.incarnation(),boot,1,BigInteger.ZERO,BigInteger.ONE,1),
            c,proof,a,TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.challengeBytes(c),c.deviceChallengeSignature()),
            TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.acceptanceBytes(c,proof,a),a.signature()));
    }
    void raw(long seq,int boot,long uptime) {
        var result=ingest.ingestBatch(userA.getId(),orgA.getId(),List.of(new ObservationIngestService.IngestItem(node.getNodeId(),seq,"SAME-CHIP",null,"UNKNOWN",incarnation.toString(),uptime,boot)));
        assertEquals(1,result.inserted());
    }
    TimeEvidenceProtocol.PhoneRequest phone(int boot,long uptime,long utc) {
        return new TimeEvidenceProtocol.PhoneRequest(orgA.getId().toString(),UUID.randomUUID().toString(),node.getNodeId().toString(),incarnation.toString(),boot,BigInteger.valueOf(uptime),utc,120,"PHONE_ESTIMATE",1);
    }
    @Test void phoneLateUploadReprojectsRawIntoEstimatedHistoricalVisitWithoutSecurityEvidence() {
        raw(1,42,900); raw(2,42,2000);
        var before=raws.findByNodeNodeIdAndSequence(node.getNodeId(),1).orElseThrow();
        assertEquals(UNKNOWN,page(0,10).items().getFirst().quality());
        var historical=sites.save(new FeedingSite(orgA,"Historical",null,null,null,null));
        var current=sites.save(new FeedingSite(orgA,"Current",null,null,null,null));
        deployments.save(new NodeDeployment(orgA,node,historical,Instant.ofEpochMilli(UTC-100000),Instant.ofEpochMilli(UTC+5000)));
        deployments.save(new NodeDeployment(orgA,node,current,Instant.ofEpochMilli(UTC+5000),null));
        var request=phone(42,1300,UTC);
        var receipt=evidence.ingestPhone(userA.getId(),request);
        assertEquals("PHONE_ESTIMATE",receipt.trustStatus()); assertFalse(receipt.boundedUtc());
        assertEquals("DUPLICATE_IDENTICAL",evidence.ingestPhone(userA.getId(),request).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM phone_time_anchors",Integer.class));
        assertEquals(0,anchors.count()); // No signed proof or verified security time manufactured.
        var estimated=page(0,10).items().getFirst(); assertEquals(ESTIMATED,estimated.quality());
        assertEquals(UTC-400,estimated.earliestUtcMs()); assertEquals(estimated.earliestUtcMs(),estimated.approximateUtcMs());
        assertEquals(historical.getId(),estimated.derivedFeedingSiteId());
        var visit=temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20).items().getFirst();
        assertEquals("ESTIMATED",visit.quality()); assertEquals("VISIT",visit.kind()); assertEquals(2,visit.observationCount());
        var after=raws.findByNodeNodeIdAndSequence(node.getNodeId(),1).orElseThrow();
        assertEquals(before.getId(),after.getId()); assertNull(after.getObservedAtMs()); assertNull(after.getFeedingSite());
        assertEquals(before.getMonotonicMs(),after.getMonotonicMs()); assertEquals(before.getClockStatus(),after.getClockStatus());
        // A wider span straddling a move must lose derived site attribution, never use today's site.
        evidence.ingestPhone(userA.getId(),phone(42,2300,UTC+11000));
        var span=page(0,10).items().getFirst(); assertEquals(ESTIMATED,span.quality());
        assertNull(span.approximateUtcMs()); assertNull(span.derivedFeedingSiteId());
        assertTrue(span.latestUtcMs()>span.earliestUtcMs()); assertEquals(2,raws.count());
        assertEquals("OBSERVATION",temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20).items().getFirst().kind());
    }
    @Test void phoneTenantNodeIncarnationBootBindingConflictingRetryAndConcurrentDuplicate() throws Exception {
        var request=phone(42,1300,UTC);
        status(403,()->evidence.ingestPhone(userB.getId(),request));
        var unknownNode=new TimeEvidenceProtocol.PhoneRequest(request.organizationId(),request.anchorId(),UUID.randomUUID().toString(),request.incarnation(),42,request.uptimeMs(),UTC,120,"PHONE_ESTIMATE",1);
        status(403,()->evidence.ingestPhone(userA.getId(),unknownNode));
        var foreign=new TimeEvidenceProtocol.PhoneRequest(orgB.getId().toString(),request.anchorId(),request.nodeId(),request.incarnation(),42,request.uptimeMs(),UTC,120,"PHONE_ESTIMATE",1);
        status(403,()->evidence.ingestPhone(userB.getId(),foreign));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->evidence.ingestPhone(userA.getId(),request));
            var two=pool.submit(()->evidence.ingestPhone(userA.getId(),request));
            assertEquals(Set.of("CREATED","DUPLICATE_IDENTICAL"),Set.of(one.get().status(),two.get().status()));
        } finally { pool.shutdownNow(); }
        for(var changed:List.of(
            new TimeEvidenceProtocol.PhoneRequest(request.organizationId(),request.anchorId(),request.nodeId(),request.incarnation(),43,request.uptimeMs(),UTC,120,"PHONE_ESTIMATE",1),
            new TimeEvidenceProtocol.PhoneRequest(request.organizationId(),request.anchorId(),request.nodeId(),UUID.randomUUID().toString(),42,request.uptimeMs(),UTC,120,"PHONE_ESTIMATE",1),
            new TimeEvidenceProtocol.PhoneRequest(request.organizationId(),request.anchorId(),request.nodeId(),request.incarnation(),42,request.uptimeMs(),UTC+1,120,"PHONE_ESTIMATE",1)))
            status(409,()->evidence.ingestPhone(userA.getId(),changed));
        raw(1,43,900); assertNotEquals(ESTIMATED,page(0,10).items().getFirst().quality());
        status(403,()->pageForForeign());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM phone_time_anchors",Integer.class));
    }
    void pageForForeign() { projections.read(userB.getId(),orgA.getId(),node.getNodeId(),0,10); }
    @Test void phoneEndpointRequiresSessionCsrfOwnerAndStrictUntrustedSchema() throws Exception {
        String json=mapper.writeValueAsString(phone(42,1300,UTC));
        assertNotEquals(200,http("POST","/api/v1/nodes/phone-time-evidence",json,true).statusCode());
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"a@example.org\",\"password\":\"test-password\"}",true).statusCode());
        assertEquals(403,http("POST","/api/v1/nodes/phone-time-evidence",json,false).statusCode());
        for(String bad:List.of(json.replaceFirst("\\{","{\"verifiedSecurityTime\":true,"),json.replace("PHONE_ESTIMATE","BACKEND_SIGNED"),
            json.replace("\"uptimeMs\":1300","\"uptimeMs\":\"1300\""),json.replace("\"bootCounter\":42","\"bootCounter\":0"),json+"{}"))
            assertEquals(400,http("POST","/api/v1/nodes/phone-time-evidence",bad,true).statusCode(),bad);
        var response=http("POST","/api/v1/nodes/phone-time-evidence",json,true); assertEquals(200,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("PHONE_ESTIMATE",mapper.readTree(response.body()).path("trustStatus").asText());
        assertEquals(0,anchors.count()); assertEquals(0,boots.count());
        var maximum=json.replace("\"uptimeMs\":1300","\"uptimeMs\":18446744073709551615");
        assertEquals(TimeEvidenceProtocol.U64_MAX,TimeEvidenceProtocol.parsePhone(maximum.getBytes(java.nio.charset.StandardCharsets.UTF_8)).uptimeMs());
    }
    TemporalProjectionService.Page page(long after,int size) { return projections.read(userA.getId(),orgA.getId(),node.getNodeId(),after,size); }
    static void status(int status,org.junit.jupiter.api.function.Executable code) {
        assertEquals(status,assertThrows(ResponseStatusException.class,code).getStatusCode().value());
    }
    @Test void temporalVisitCapacityNeverSilentlyTruncatesHistory() {
        var rows=new ArrayList<Object[]>();
        for(long sequence=1;sequence<=4097;sequence++) rows.add(new Object[]{UUID.randomUUID(),orgA.getId(),node.getNodeId(),sequence,"SAME-CHIP"});
        jdbc.batchUpdate("INSERT INTO raw_observations(id,organization_id,node_id,sequence,chip_id,clock_status) VALUES(?,?,?,?,?,'UNKNOWN')",rows);
        status(503,()->temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20));
        assertEquals(4097,raws.count());
    }
    @Test void temporalVisitsLateEvidenceHistoricalSitePaginationAndTenantIsolation() throws Exception {
        raw(1,42,900); raw(2,42,2000);
        var unknown=temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,1);
        assertEquals("OBSERVATION",unknown.items().getFirst().kind());
        assertEquals("UNKNOWN",unknown.items().getFirst().quality()); assertEquals(1,unknown.nextOffset());
        assertEquals(1,temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",1,1).items().size());
        var site=sites.save(new FeedingSite(orgA,"Historical",null,null,null,null));
        deployments.save(new NodeDeployment(orgA,node,site,Instant.ofEpochMilli(UTC-1000000),null));
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r);
        var reconstructed=temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,1);
        assertEquals(1,reconstructed.items().size()); assertNull(reconstructed.nextOffset());
        var visit=reconstructed.items().getFirst(); assertEquals("VISIT",visit.kind()); assertEquals(2,visit.observationCount());
        assertEquals(site.getId(),visit.feedingSiteId()); assertEquals("RECONSTRUCTED",visit.quality());
        assertTrue(visit.earliestUtcMs()<visit.latestUtcMs());
        assertEquals(reconstructed,temporalVisits.read(userA.getId(),orgA.getId(),"same-chip",0,1));
        assertEquals("DUPLICATE_IDENTICAL",evidence.ingest(userA.getId(),r).status());
        assertEquals(reconstructed,temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,1));
        assertTrue(raws.findAll().stream().allMatch(o->o.getObservedAtMs()==null && o.getFeedingSite()==null));
        status(403,()->temporalVisits.read(userB.getId(),orgA.getId(),"SAME-CHIP",0,20));
        assertTrue(temporalVisits.read(userB.getId(),orgB.getId(),"SAME-CHIP",0,20).items().isEmpty());
        for(int offset:List.of(-1)) status(400,()->temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",offset,1));
        status(400,()->temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,101));
        assertTrue(temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",Integer.MAX_VALUE,20).items().isEmpty());
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"a@example.org\",\"password\":\"test-password\"}",true).statusCode());
        var response=http("GET","/api/v1/organizations/"+orgA.getId()+"/visits/temporal?chipId=SAME-CHIP",null,false);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        var visitJson=mapper.readTree(response.body()).path("items").get(0);
        assertTrue(visitJson.path("earliestUtcMs").isString());
        assertTrue(visitJson.path("observations").get(0).path("time").path("sequence").isString());
        var admin=http("GET","/admin/visits/temporal?chipId=SAME-CHIP",null,false);
        assertEquals(200,admin.statusCode(),admin.body());
        assertTrue(admin.body().contains("Besuch mit rekonstruiertem Zeitfenster"));
        assertTrue(admin.body().contains("data-utc-ms="));
        assertTrue(admin.body().contains("Herkunft und relative Reihenfolge"));
        assertFalse(admin.body().contains("1970"));
        assertEquals(200,http("GET","/admin-temporal-times.js",null,false).statusCode());
        assertEquals(403,http("GET","/api/v1/organizations/"+orgB.getId()+"/visits/temporal?chipId=SAME-CHIP",null,false).statusCode());
    }
    @Test void lateAnchorAfterRawAckReprojectsWithoutMutationAndExactRetryIsNoop() {
        raw(1,42,900); raw(2,42,1000);
        var original=raws.findByNodeNodeIdAndSequence(node.getNodeId(),1).orElseThrow();
        assertEquals(UNKNOWN,page(0,10).items().getFirst().quality());
        var r=request(42,1000,1300,UTC);
        assertEquals("CREATED",evidence.ingest(userA.getId(),r).status());
        assertEquals("DUPLICATE_IDENTICAL",evidence.ingest(userA.getId(),r).status());
        assertEquals(1,anchors.count()); assertEquals(1,boots.count());
        var p=page(0,1); assertEquals(1,p.items().size()); assertEquals(1L,p.nextAfterSequence());
        var v=p.items().getFirst(); assertEquals(RECONSTRUCTED,v.quality());
        assertEquals(UTC-502,v.earliestUtcMs()); assertEquals(UTC+2,v.latestUtcMs()); assertNull(v.approximateUtcMs());
        assertEquals(2,page(1,1).items().getFirst().sequence()); assertEquals(p,page(0,1));
        var stored=raws.findByNodeNodeIdAndSequence(node.getNodeId(),1).orElseThrow();
        assertEquals(original.getId(),stored.getId()); assertTrue(stored.samePayload("SAME-CHIP",null,"UNKNOWN",incarnation.toString(),900L,42));
        assertNull(stored.getFeedingSite()); assertNull(stored.getDeployment());
        var a=anchors.findAll().getFirst(); assertEquals(r.proof(),a.getProof()); assertEquals(r.acceptanceBytes(),a.getAcceptanceBytes()); assertEquals(r.challengeBytes(),a.getChallengeBytes());
        assertEquals(1,ingest.ingestBatch(userA.getId(),orgA.getId(),List.of(new ObservationIngestService.IngestItem(node.getNodeId(),1,"SAME-CHIP",null,"UNKNOWN",incarnation.toString(),900L,42))).duplicates());
    }
    @Test void firmwareMetadataIsDurableAuditOnlyAndConflictingRetriesFail() throws Exception {
        var r=request(42,1000,1300,UTC);
        String metadata="{\"anchorId\":\""+r.acceptance().anchorId()+"\",\"formatVersion\":1,\"verifiedSecurityTime\":true,\"proofIdOrDigest\":\""
                +NodeTimeProtocol.json(java.util.Base64.getUrlDecoder().decode(r.proof().split("\\.")[1])).path("jti").asText()
                +"\",\"uptimeMs\":1300,\"utcMs\":"+UTC+",\"source\":0,\"uncertaintyMinusMs\":18446744073709551615,\"uncertaintyPlusMs\":18446744073709551615,\"driftPpmBound\":4294967295}";
        var withMetadata=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),r.acceptance(),r.challengeBytes(),r.acceptanceBytes(),metadata);
        var parsed=TimeEvidenceProtocol.parse(mapper.writeValueAsBytes(withMetadata));
        assertEquals(metadata,parsed.nodeMetadata());
        assertEquals("CREATED",evidence.ingest(userA.getId(),parsed).status());
        var saved=anchors.findAll().getFirst();
        assertEquals(metadata,saved.getNodeMetadata());
        // Sender's unsigned maximum bounds never change the independently calibrated policy.
        assertNotEquals(new BigInteger("18446744073709551615"),BigInteger.valueOf(saved.getUncertaintyMinusMs()));
        assertEquals("DUPLICATE_IDENTICAL",evidence.ingest(userA.getId(),parsed).status());
        var conflicting=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),r.acceptance(),r.challengeBytes(),r.acceptanceBytes(),metadata.replace("4294967295","1"));
        status(409,()->evidence.ingest(userA.getId(),conflicting));
        var wrong=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),r.acceptance(),r.challengeBytes(),r.acceptanceBytes(),metadata.replace("\"uptimeMs\":1300","\"uptimeMs\":1301"));
        status(400,()->evidence.ingest(userA.getId(),wrong));
        assertEquals(metadata,anchors.findAll().getFirst().getNodeMetadata());
    }
    @Test void evidenceBeforeRawAndSeparateBootRetriesRemainDurable() {
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r); raw(1,42,900);
        assertEquals(RECONSTRUCTED,page(0,10).items().getFirst().quality());
        var b=new TimeEvidenceProtocol.Boot(r.boot().nodeId(),r.boot().incarnation(),43,3,BigInteger.ZERO,BigInteger.valueOf(2),1);
        var bootRequest=new TimeEvidenceProtocol.BootRequest(orgA.getId().toString(),b);
        assertEquals("CREATED",evidence.ingestBoot(userA.getId(),bootRequest).status());
        assertEquals("DUPLICATE_IDENTICAL",evidence.ingestBoot(userA.getId(),bootRequest).status());
        var changed=new TimeEvidenceProtocol.Boot(b.nodeId(),b.incarnation(),43,4,BigInteger.ZERO,b.firstPossibleSequence(),1);
        status(409,()->evidence.ingestBoot(userA.getId(),new TimeEvidenceProtocol.BootRequest(orgA.getId().toString(),changed)));
        assertEquals(2,boots.count()); assertEquals(1,anchors.count());
    }
    @Test void secondAnchorNarrowsAndContradictionNeverOptimisticallyWins() {
        raw(1,1,900);
        evidence.ingest(userA.getId(),request(1,1000,1300,UTC));
        var before=page(0,10).items().getFirst();
        evidence.ingest(userA.getId(),request(1,1800,2000,UTC+650));
        var after=page(0,10).items().getFirst();
        assertTrue(after.earliestUtcMs()>=before.earliestUtcMs()); assertTrue(after.latestUtcMs()<before.latestUtcMs());
        evidence.ingest(userA.getId(),request(1,2800,3000,UTC+9000));
        assertEquals(CONFLICT,page(0,10).items().getFirst().quality()); assertNull(page(0,10).items().getFirst().earliestUtcMs());
        assertEquals("OBSERVATION",temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20).items().getFirst().kind());
    }
    @Test void twoRebootsUseOnlyOuterBoundsAndUnknownShutdownDurations() {
        raw(1,1,1000); raw(2,5,1200); raw(3,5,9000); raw(4,9,1000);
        evidence.ingest(userA.getId(),request(1,1000,1300,UTC));
        evidence.ingest(userA.getId(),request(9,1000,1300,UTC+86400000));
        var views=page(0,10).items(); assertEquals(RECONSTRUCTED,views.get(3).quality());
        assertEquals(BOUNDED,views.get(1).quality()); assertEquals(BOUNDED,views.get(2).quality());
        assertEquals(7800L,views.get(2).monotonicMs()-views.get(1).monotonicMs()); assertNull(views.get(1).approximateUtcMs());
        var history=temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20);
        assertEquals(4,history.items().size());
        assertEquals("BOUNDED",history.items().get(1).quality());
        assertEquals("OBSERVATION",history.items().get(1).kind());
        assertEquals("BOUNDED",history.items().get(2).quality());
        assertTrue(history.items().stream().allMatch(i->i.observationCount()==1));
    }
    @Test void adminShowsOneSidedBoundsAndConflictsWithoutInventedTimeOrSite() throws Exception {
        raw(1,1,1000); raw(2,5,1200);
        evidence.ingest(userA.getId(),request(1,1000,1300,UTC));
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"a@example.org\",\"password\":\"test-password\"}",true).statusCode());
        var bounded=page(1,1).items().getFirst();
        assertEquals(UNKNOWN,bounded.quality()); assertNull(bounded.latestUtcMs());
        var response=http("GET","/admin/observations?sequence=2",null,false);
        assertEquals(200,response.statusCode());
        String html=response.body();
        assertTrue(html.contains("UNKNOWN"));
        assertTrue(html.contains("Keine belegte Obergrenze"));
        assertTrue(html.contains(Instant.ofEpochMilli(bounded.earliestUtcMs()).toString()));
        assertTrue(html.contains("Futterstelle nicht eindeutig bestimmbar"));
        evidence.ingest(userA.getId(),request(1,2800,3000,UTC+9000));
        response=http("GET","/admin/observations?sequence=1",null,false);
        assertEquals(200,response.statusCode());
        assertTrue(response.body().contains("CONFLICT"));
        assertTrue(response.body().contains("Zeitbelege widersprechen sich"));
        assertFalse(response.body().contains("Frühestens:"));
        assertTrue(response.body().contains("Futterstelle nicht eindeutig bestimmbar"));
    }
    @Test void reversedUptimeAcrossPaginationAndBootOrderYieldConflict() {
        raw(1,1,1000); raw(2,1,900); evidence.ingest(userA.getId(),request(1,1000,1300,UTC));
        assertEquals(CONFLICT,page(1,1).items().getFirst().quality());
    }
    @Test void bootRollbackIsConflictButCounterGapsAreAllowed() {
        raw(1,9,100); raw(2,2,200); evidence.ingest(userA.getId(),request(9,1000,1300,UTC));
        assertEquals(CONFLICT,page(0,1).items().getFirst().quality());
    }
    @Test void historicalDeploymentRequiresEntireIntervalAndPreservesFrozenRawAttribution() {
        var old=sites.save(new FeedingSite(orgA,"Old",null,null,null,null));
        var current=sites.save(new FeedingSite(orgA,"Current",null,null,null,null));
        var d=deployments.save(new NodeDeployment(orgA,node,old,Instant.ofEpochMilli(UTC-10000),Instant.ofEpochMilli(UTC+10000)));
        deployments.save(new NodeDeployment(orgA,node,current,Instant.ofEpochMilli(UTC+10000),null));
        raw(1,42,900); evidence.ingest(userA.getId(),request(42,1000,1300,UTC));
        var view=page(0,10).items().getFirst(); assertEquals(old.getId(),view.derivedFeedingSiteId()); assertEquals(d.getId(),view.derivedDeploymentId());
        assertNull(raws.findByNodeNodeIdAndSequence(node.getNodeId(),1).orElseThrow().getFeedingSite());
        deployments.save(new NodeDeployment(orgA,node,current,Instant.ofEpochMilli(UTC-200),Instant.ofEpochMilli(UTC+200)));
        assertNull(page(0,10).items().getFirst().derivedFeedingSiteId());
        var uncertain=temporalVisits.read(userA.getId(),orgA.getId(),"SAME-CHIP",0,20).items().getFirst();
        assertNull(uncertain.feedingSiteId()); assertEquals("OBSERVATION",uncertain.kind());
    }
    @Test void frozenSiteSurvivesUnknownStrictProjection() {
        var site=sites.save(new FeedingSite(orgA,"Frozen",null,null,null,null));
        var d=deployments.save(new NodeDeployment(orgA,node,site,Instant.ofEpochMilli(UTC-10000),null));
        raws.save(new RawObservation(orgA,node,1,"SAME-CHIP",UTC,"SYNCED",null,null,null,site,d));
        var v=page(0,1).items().getFirst(); assertEquals(UNKNOWN,v.quality()); assertNull(v.earliestUtcMs()); assertEquals(site.getId(),v.derivedFeedingSiteId());
    }
    @Test void tenantIsolationSameChipSysadminAndIdentityGuessing() {
        cats.save(new Cat(orgA,"SAME-CHIP","A",null,null));
        cats.save(new Cat(orgB,"SAME-CHIP","B",null,null));
        var foreignKey=EcKeyUtils.generateP256(); var pub=(ECPublicKey)foreignKey.getPublic();
        var foreign=new NodeDevice(UUID.randomUUID(),EcKeyUtils.xOf(pub),EcKeyUtils.yOf(pub),EcKeyUtils.fingerprintOf(pub),"test");
        foreign.claim(orgB); nodes.save(foreign);
        raws.save(new RawObservation(orgB,foreign,1,"SAME-CHIP",null,"UNKNOWN",UUID.randomUUID().toString(),100L,1,null,null));
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r); raw(1,42,900);
        status(403,()->projections.read(userA.getId(),orgA.getId(),foreign.getNodeId(),0,10));
        status(403,()->projections.read(userA.getId(),orgA.getId(),UUID.randomUUID(),0,10));
        status(403,()->projections.read(userB.getId(),orgB.getId(),node.getNodeId(),0,10));
        status(403,()->evidence.ingest(userB.getId(),r));
        jdbc.update("INSERT INTO app_user_system_roles(user_id,role,enabled) VALUES (?,?,true)",userB.getId(),"SYSADMIN");
        status(403,()->projections.read(userB.getId(),orgA.getId(),node.getNodeId(),0,10));
        var wrongOrg=new TimeEvidenceProtocol.Request(orgB.getId().toString(),r.boot(),r.challenge(),r.proof(),r.acceptance(),r.challengeBytes(),r.acceptanceBytes());
        status(403,()->evidence.ingest(userB.getId(),wrongOrg));
        assertEquals(1,anchors.count());
    }
    @Test void disabledMembershipOrgAccountAndUnclaimedNodeFailClosed() {
        var r=request(42,1000,1300,UTC);
        var m=memberships.findByOrganizationIdAndUserId(orgA.getId(),userA.getId()).orElseThrow(); m.disable(); memberships.save(m);
        status(403,()->evidence.ingest(userA.getId(),r)); status(403,()->page(0,10));
        m.activate(); memberships.save(m);
        jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?",orgA.getId()); status(403,()->evidence.ingest(userA.getId(),r));
        jdbc.update("UPDATE organizations SET status='ACTIVE' WHERE id=?",orgA.getId());
        jdbc.update("UPDATE app_users SET status='DISABLED' WHERE id=?",userA.getId()); status(403,()->evidence.ingest(userA.getId(),r));
        jdbc.update("UPDATE app_users SET status='ACTIVE' WHERE id=?",userA.getId());
        jdbc.update("UPDATE nodes SET state='UNCLAIMED', organization_id=NULL WHERE node_id=?",node.getNodeId()); status(403,()->evidence.ingest(userA.getId(),r));
        assertEquals(0,anchors.count());
    }
    @Test void preClaimProofAndRotatedRegisteredKeyCannotCertifyOldEvidence() {
        status(403,()->evidence.ingest(userA.getId(),request(42,1000,1300,UTC-20000000)));
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r); raw(1,42,900);
        var pub=(ECPublicKey)EcKeyUtils.generateP256().getPublic();
        jdbc.update("UPDATE nodes SET public_key_x=?,public_key_y=?,fingerprint=? WHERE node_id=?",EcKeyUtils.xOf(pub),EcKeyUtils.yOf(pub),EcKeyUtils.fingerprintOf(pub),node.getNodeId());
        status(403,()->evidence.ingest(userA.getId(),r)); assertEquals(UNKNOWN,page(0,10).items().getFirst().quality());
    }
    @Test void bareProofForgedAcceptanceChangedNonceUptimeAndWrongIssuerAreRejected() {
        var r=request(42,1000,1300,UTC);
        String json=mapper.writeValueAsString(r);
        assertThrows(IllegalArgumentException.class,()->TimeEvidenceProtocol.parse(json.replace("\"signature\":\""+r.acceptance().signature()+"\"","\"signature\":\""+EcKeyUtils.b64u(new byte[64])+"\"").getBytes()));
        var a=r.acceptance();
        var forged=new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),a.bootCounter(),a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),EcKeyUtils.b64u(new byte[64]),1);
        var invalid=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),forged,r.challengeBytes(),
            TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.acceptanceBytes(r.challenge(),r.proof(),forged),forged.signature()));
        status(403,()->evidence.ingest(userA.getId(),invalid)); assertEquals(0,boots.count()); assertEquals(0,anchors.count());
        assertThrows(IllegalArgumentException.class,()->TimeEvidenceProtocol.parse(("{\"organizationId\":\""+orgA.getId()+"\",\"proof\":\""+r.proof()+"\"}").getBytes()));
    }
    @Test void concurrentIdenticalUploadsAreOneInsertAndInterruptedTransactionRollsBack() throws Exception {
        var r=request(42,1000,1300,UTC);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var futures=pool.invokeAll(List.of(()->evidence.ingest(userA.getId(),r),()->evidence.ingest(userA.getId(),r)));
            var statuses=new HashSet<String>(); for(var f:futures) statuses.add(((TimeEvidenceService.Receipt)f.get()).status());
            assertEquals(Set.of("CREATED","DUPLICATE_IDENTICAL"),statuses);
        }
        assertEquals(1,anchors.count()); assertEquals(1,boots.count());
        // Database rejects an invalid constraint without mutating the accepted row.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE time_anchors SET uncertainty_minus_ms=0"));
        assertEquals(101L,anchors.findAll().getFirst().getUncertaintyMinusMs());
        var second=request(43,1000,1300,UTC+10000);
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            evidence.ingest(userA.getId(),second);
            throw new IllegalStateException("simulated interruption before commit");
        }));
        assertEquals(1,boots.count()); assertEquals(1,anchors.count());
        assertEquals("CREATED",evidence.ingest(userA.getId(),second).status());
    }
    @Test void identicalIdWithDifferentValidAcceptanceConflictsAndMissingBudgetsStayUnknown() {
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r);
        var a=r.acceptance();
        var changed=new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),a.bootCounter(),a.proofDigest(),a.challengeNonceDigest(),BigInteger.valueOf(1400),a.anchorId(),EcKeyUtils.b64u(new byte[64]),1);
        changed=new NodeTimeProtocol.Acceptance(changed.nodeId(),changed.incarnation(),changed.bootCounter(),changed.proofDigest(),changed.challengeNonceDigest(),changed.acceptedUptimeMs(),changed.anchorId(),EcKeyUtils.b64u(EcKeyUtils.signRaw(key.getPrivate(),NodeTimeProtocol.acceptanceBytes(r.challenge(),r.proof(),changed))),1);
        var conflict=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),changed,r.challengeBytes(),TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.acceptanceBytes(r.challenge(),r.proof(),changed),changed.signature()));
        status(409,()->evidence.ingest(userA.getId(),conflict)); assertEquals(1300,anchors.findAll().getFirst().getUptimeMs().longValueExact());
        org.mockito.Mockito.doReturn(null).when(uncertainty).budget();
        evidence.ingest(userA.getId(),request(43,1000,1300,UTC+10000)); raw(1,43,900);
        assertEquals(UNKNOWN,page(0,1).items().getFirst().quality());
        assertNull(anchors.findAll().stream().filter(t->t.getBootCounter()==43).findFirst().orElseThrow().getDriftPpm());
    }
    @Test void nonceReplayWithDifferentProofCannotCreateSecondInstalledAnchor() {
        var r=request(42,1000,1300,UTC); evidence.ingest(userA.getId(),r);
        String proof=issuer.issueTimeProof(r.challenge(),UTC+100);
        var a=r.acceptance();
        var duplicate=new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),a.bootCounter(),EcKeyUtils.b64u(NodeTimeProtocol.digest(proof.getBytes(java.nio.charset.StandardCharsets.UTF_8))),a.challengeNonceDigest(),BigInteger.valueOf(1500),UUID.randomUUID().toString(),EcKeyUtils.b64u(new byte[64]),1);
        duplicate=new NodeTimeProtocol.Acceptance(duplicate.nodeId(),duplicate.incarnation(),duplicate.bootCounter(),duplicate.proofDigest(),duplicate.challengeNonceDigest(),duplicate.acceptedUptimeMs(),duplicate.anchorId(),EcKeyUtils.b64u(EcKeyUtils.signRaw(key.getPrivate(),NodeTimeProtocol.acceptanceBytes(r.challenge(),proof,duplicate))),1);
        var replay=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),proof,duplicate,r.challengeBytes(),TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.acceptanceBytes(r.challenge(),proof,duplicate),duplicate.signature()));
        status(409,()->evidence.ingest(userA.getId(),replay)); assertEquals(1,anchors.count());
    }
    HttpResponse<String> http(String method,String path,String body,boolean csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(csrf) {
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
            builder.header("X-XSRF-TOKEN",mapper.readTree(response.body()).path("token").asText());
        }
        if(method.equals("POST")) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void preClaimContinuityHintRequiresOwnerAndVerifiedEvidenceAndNeverAcceptsOldAnchor() throws Exception {
        jdbc.update("UPDATE nodes SET claimed_at=? WHERE node_id=?",java.sql.Timestamp.from(Instant.ofEpochMilli(UTC+1)),node.getNodeId());
        var r=request(42,1000,1300,UTC); String json=mapper.writeValueAsString(r);
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"a@example.org\",\"password\":\"test-password\"}",true).statusCode());
        var missingCsrf=http("POST","/api/v1/nodes/time-evidence",json,false);
        assertEquals(403,missingCsrf.statusCode()); assertTrue(missingCsrf.headers().firstValue("X-MM-Time-Evidence").isEmpty());
        var denied=http("POST","/api/v1/nodes/time-evidence",json,true);
        assertEquals(403,denied.statusCode()); assertEquals("PRE_CLAIM_CONTINUITY_UNVERIFIED",denied.headers().firstValue("X-MM-Time-Evidence").orElseThrow());
        assertEquals(0,anchors.count()); assertEquals(0,boots.count());
        var forged=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof()+"x",r.acceptance(),r.challengeBytes(),r.acceptanceBytes());
        var badSignature=http("POST","/api/v1/nodes/time-evidence",mapper.writeValueAsString(forged),true);
        assertEquals(400,badSignature.statusCode()); assertTrue(badSignature.headers().firstValue("X-MM-Time-Evidence").isEmpty());
        var a=r.acceptance();
        var invalidAcceptance=new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),a.bootCounter(),a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),EcKeyUtils.b64u(new byte[64]),1);
        var invalidSignature=new TimeEvidenceProtocol.Request(r.organizationId(),r.boot(),r.challenge(),r.proof(),invalidAcceptance,r.challengeBytes(),TimeEvidenceProtocol.signedBytes(NodeTimeProtocol.acceptanceBytes(r.challenge(),r.proof(),invalidAcceptance),invalidAcceptance.signature()));
        var badCryptographicSignature=http("POST","/api/v1/nodes/time-evidence",mapper.writeValueAsString(invalidSignature),true);
        assertEquals(403,badCryptographicSignature.statusCode()); assertTrue(badCryptographicSignature.headers().firstValue("X-MM-Time-Evidence").isEmpty());
        var fresh=http("POST","/api/v1/nodes/time-evidence",mapper.writeValueAsString(request(42,1400,1700,UTC+2)),true);
        assertEquals(200,fresh.statusCode(),fresh.body()); assertEquals(1,anchors.count());
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"b@example.org\",\"password\":\"test-password\"}",true).statusCode());
        var foreign=http("POST","/api/v1/nodes/time-evidence",json,true);
        assertEquals(403,foreign.statusCode()); assertTrue(foreign.headers().firstValue("X-MM-Time-Evidence").isEmpty());
    }
    @Test void httpStrictSchemaCsrfSizeAuthPaginationAndOpenApi() throws Exception {
        var r=request(42,1000,1300,UTC); String json=mapper.writeValueAsString(r);
        assertEquals(401,http("POST","/api/v1/nodes/time-evidence",json,true).statusCode());
        assertEquals(200,http("POST","/api/v1/auth/login","{\"email\":\"a@example.org\",\"password\":\"test-password\"}",true).statusCode());
        raw(1,42,900);
        var unknown=http("GET","/api/v1/nodes/"+node.getNodeId()+"/temporal-projections?organizationId="+orgA.getId(),null,false);
        assertEquals(200,unknown.statusCode());
        var unknownItem=mapper.readTree(unknown.body()).path("items").get(0);
        assertEquals("UNKNOWN",unknownItem.path("quality").asText());
        assertTrue(unknownItem.has("earliestUtcMs")); assertTrue(unknownItem.path("earliestUtcMs").isNull());
        assertTrue(unknownItem.path("latestUtcMs").isNull()); assertTrue(unknownItem.path("approximateUtcMs").isNull());
        assertEquals(403,http("POST","/api/v1/nodes/time-evidence",json,false).statusCode());
        assertEquals(413,http("POST","/api/v1/nodes/time-evidence"," ".repeat(8193),true).statusCode());
        for(String bad:List.of(json.replaceFirst("\\{","{\"verified\":true,"),json+"{}",json.replace("\"bootCounter\":42","\"bootCounter\":42.5"),json.replace("\"acceptedUptimeMs\":1300","\"acceptedUptimeMs\":\"1300\""),json.replaceFirst("\\{","{\"organizationId\":\""+orgA.getId()+"\","))) {
            assertEquals(400,http("POST","/api/v1/nodes/time-evidence",bad,true).statusCode(),bad);
        }
        var good=http("POST","/api/v1/nodes/time-evidence",json,true); assertEquals(200,good.statusCode(),good.body());
        assertEquals("no-store",good.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(400,http("GET","/api/v1/nodes/"+node.getNodeId()+"/temporal-projections?organizationId="+orgA.getId()+"&limit=101",null,false).statusCode());
        var spec=http("GET","/api/v1/openapi",null,false); assertEquals(200,spec.statusCode());
        assertTrue(spec.body().contains("ingestNodeTimeEvidence")); assertTrue(spec.body().contains("listNodeTemporalProjections"));
        var schemas=mapper.readTree(spec.body()).at("/components/schemas");
        for(String field:List.of("earliestUtcMs","latestUtcMs","approximateUtcMs","incarnation","bootCounter","monotonicMs","derivedFeedingSiteId","derivedDeploymentId")) {
            var property=schemas.path("Projection").path("properties").path(field);
            assertTrue(property.path("nullable").asBoolean() || property.path("type").toString().contains("\"null\""),property.toString());
        }
        var cursor=schemas.path("Page").path("properties").path("nextAfterSequence");
        assertTrue(cursor.path("nullable").asBoolean() || cursor.path("type").toString().contains("\"null\""),cursor.toString());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi.json"),spec.body()+"\n");
    }
}

