package org.miezmerker.backend;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.net.*;
import java.net.http.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.service.CollectorSyncEventService;
import org.miezmerker.backend.service.CollectorSyncEventService.Event;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("node") @Tag("auth") @Tag("ingest")
class CollectorSyncEventTest {
    @Autowired CollectorSyncEventService service;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository orgs;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    Organization org, foreign;
    AppUser admin, member, other;
    FeedingSite x, y;
    NodeDevice a, b;
    Instant now;
    UUID incarnation = UUID.randomUUID();
    @BeforeEach void seed() {
        cleaner.clean(); now=Instant.now();
        org=orgs.save(new Organization("sync-org", "Sync", null));
        foreign=orgs.save(new Organization("sync-other", "Other", null));
        admin=user("sync-admin@example.org", MembershipRole.ADMIN);
        member=user("sync-member@example.org", MembershipRole.MEMBER);
        other=user("sync-other@example.org", MembershipRole.MEMBER);
        x=sites.save(new FeedingSite(org, "X", null, null, null, null));
        y=sites.save(new FeedingSite(org, "Y", null, null, null, null));
        a=node(); b=node();
        deploy(a,x,now.minusSeconds(40*86400L),null);
        deploy(b,x,now.minusSeconds(40*86400L),null);
    }
    AppUser user(String email, MembershipRole role) {
        var u=users.save(new AppUser(email,passwords.encode("password-test-123")));
        memberships.save(new OrganizationMembership(org,u,role,MembershipStatus.ACTIVE)); return u;
    }
    NodeDevice node() {
        var n=new NodeDevice(UUID.randomUUID(),"a".repeat(64),"b".repeat(64),UUID.randomUUID().toString(),"test");
        n.claim(org); n=nodes.saveAndFlush(n);
        jdbc.update("UPDATE nodes SET claimed_at=? WHERE node_id=?",java.sql.Timestamp.from(now.minusSeconds(40*86400L)),n.getNodeId());
        return n;
    }
    void deploy(NodeDevice node, FeedingSite site, Instant from, Instant until) {
        deployments.saveAndFlush(new NodeDeployment(org,node,site,from,until));
    }
    Event event(AppUser u, NodeDevice n, Instant time) {
        return new Event(UUID.randomUUID(),org.getId(),u.getId(),n.getNodeId(),incarnation,time.toEpochMilli(),"PHONE_ESTIMATE");
    }
    void status(int expected, Runnable call) {
        assertEquals(expected,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());
    }
    @Test void idempotentImmutableIncludingIncarnationAndConcurrentRetries() throws Exception {
        var e=event(member,a,now);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->service.ingest(member.getId(),e));
            var f2=pool.submit(()->service.ingest(member.getId(),e));
            assertEquals(Set.of("CREATED","DUPLICATE_IDENTICAL"),Set.of(f1.get().status(),f2.get().status()));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM collector_sync_events",Integer.class));
        status(409,()->service.ingest(member.getId(),new Event(e.eventId(),e.organizationId(),e.ownerUserId(),e.nodeId(),UUID.randomUUID(),e.occurredAt(),e.timeQuality())));
        status(409,()->service.ingest(member.getId(),new Event(e.eventId(),e.organizationId(),e.ownerUserId(),b.getNodeId(),e.incarnation(),e.occurredAt(),e.timeQuality())));
        status(409,()->service.ingest(member.getId(),new Event(e.eventId(),e.organizationId(),e.ownerUserId(),e.nodeId(),e.incarnation(),e.occurredAt()+1,e.timeQuality())));
    }
    @Test void multipleBowlsRepeatedSyncsDistinctActivePeopleAndLimit() {
        service.ingest(member.getId(),event(member,a,now.minusSeconds(10)));
        service.ingest(member.getId(),event(member,b,now));
        service.ingest(other.getId(),event(other,b,now));
        var result=service.recipients(admin.getId(),org.getId(),x.getId(),20);
        assertEquals(2,result.recipients().size());
        assertEquals(List.of(other.getId()),service.recipients(admin.getId(),org.getId(),x.getId(),1).recipients().stream().map(r->r.userId()).toList());
        service.ingest(member.getId(),event(member,a,Instant.now()));
        assertEquals(List.of(member.getId()),service.recipients(admin.getId(),org.getId(),x.getId(),1).recipients().stream().map(r->r.userId()).toList());
        assertTrue(result.provenance().contains("NOT_ATTENDANCE_PROOF"));
        assertTrue(result.ordering().contains("UNORDERED"));
        var m=memberships.findByOrganizationIdAndUserId(org.getId(),member.getId()).orElseThrow();
        m.disable(); memberships.saveAndFlush(m);
        assertEquals(List.of(other.getId()),service.recipients(admin.getId(),org.getId(),x.getId(),20).recipients().stream().map(r->r.userId()).toList());
        other.setStatus(UserStatus.DISABLED); users.saveAndFlush(other);
        assertTrue(service.recipients(admin.getId(),org.getId(),x.getId(),20).recipients().isEmpty());
        status(400,()->service.recipients(admin.getId(),org.getId(),x.getId(),21));
        status(400,()->service.recipients(admin.getId(),org.getId(),x.getId(),0));
    }
    @Test void historicalMoveBeforeAfterBoundaryAndDelayedUploadNeverGuessCurrentSite() {
        deployments.deleteAllInBatch();
        Instant move=now.minusSeconds(3600);
        deploy(a,x,now.minusSeconds(40*86400L),move); deploy(a,y,move,null);
        // Recent post-move estimate resolves Y; delayed pre-move report spans the move.
        assertEquals("RESOLVED_ESTIMATE",service.ingest(member.getId(),event(member,a,now)).resolution());
        assertEquals("AMBIGUOUS",service.ingest(other.getId(),event(other,a,move.minusSeconds(60))).resolution());
        assertTrue(service.recipients(admin.getId(),org.getId(),x.getId(),20).recipients().isEmpty());
        assertEquals(List.of(member.getId()),service.recipients(admin.getId(),org.getId(),y.getId(),20).recipients().stream().map(r->r.userId()).toList());
        // Honest offline capture remains ambiguous even with a misleading phone UTC
        // that happens to look current when the event is finally uploaded.
        var offline=event(other,a,now);
        assertEquals("AMBIGUOUS",service.ingest(other.getId(),new Event(offline.eventId(),org.getId(),other.getId(),a.getNodeId(),incarnation,offline.occurredAt(),"OFFLINE_ESTIMATE")).resolution());
        // Before a later move, frozen X report survives an ordinary subsequent move.
        deploy(b,x,now.minusSeconds(40*86400L),null);
        service.ingest(other.getId(),event(other,b,now));
        var d=deployments.findByNodeNodeId(b.getNodeId()).getFirst(); d.setValidUntil(now.plusSeconds(60)); deployments.saveAndFlush(d);
        deploy(b,y,now.plusSeconds(60),null);
        assertEquals(1,service.recipients(admin.getId(),org.getId(),x.getId(),20).recipients().size());
    }
    @Test void uncertainClockBoundaryUnassignedAndOfflineStableHistory() {
        var e=event(member,a,now);
        assertEquals("UNRESOLVED",service.ingest(member.getId(),new Event(e.eventId(),e.organizationId(),e.ownerUserId(),e.nodeId(),e.incarnation(),e.occurredAt(),"UNKNOWN")).resolution());
        assertEquals("UNRESOLVED",service.ingest(member.getId(),event(member,a,now.plusSeconds(900))).resolution());
        assertEquals("RESOLVED_ESTIMATE",service.ingest(member.getId(),event(member,a,now.minusSeconds(86400))).resolution());
        assertEquals(1,service.recipients(admin.getId(),org.getId(),x.getId(),5).recipients().size());
        deployments.deleteAllInBatch();
        deploy(b,y,now.minusSeconds(60),null);
        assertEquals("UNRESOLVED",service.ingest(member.getId(),event(member,a,now)).resolution());
        deploy(a,x,now.minusSeconds(86400),now.minusSeconds(60));
        deploy(a,y,now.minusSeconds(60),null);
        assertEquals("AMBIGUOUS",service.ingest(member.getId(),event(member,a,now)).resolution());
    }
    @Test void retrospectiveDeploymentCorrectionExcludesStaleAttribution() {
        service.ingest(member.getId(),event(member,a,now));
        assertEquals(1,service.recipients(admin.getId(),org.getId(),x.getId(),5).recipients().size());
        deployments.deleteAllInBatch();
        deploy(a,y,now.minusSeconds(40*86400L),null);
        assertTrue(service.recipients(admin.getId(),org.getId(),x.getId(),5).recipients().isEmpty());
        assertTrue(service.recipients(admin.getId(),org.getId(),y.getId(),5).recipients().isEmpty());
    }
    @Test void tenantOwnerRevocationAndAdminGates() {
        var e=event(member,a,now);
        jdbc.update("INSERT INTO app_user_system_roles(user_id,role,enabled) VALUES (?, 'SYSADMIN', true)",member.getId());
        status(403,()->service.ingest(other.getId(),e));
        status(403,()->service.ingest(member.getId(),new Event(e.eventId(),foreign.getId(),member.getId(),e.nodeId(),e.incarnation(),e.occurredAt(),e.timeQuality())));
        var n=nodes.findById(a.getNodeId()).orElseThrow(); n.claim(foreign); nodes.saveAndFlush(n);
        status(404,()->service.ingest(member.getId(),e));
        status(403,()->service.recipients(member.getId(),org.getId(),x.getId(),5));
        status(403,()->service.recipients(admin.getId(),foreign.getId(),x.getId(),5));
        status(404,()->service.recipients(admin.getId(),org.getId(),UUID.randomUUID(),5));
        var m=memberships.findByOrganizationIdAndUserId(org.getId(),member.getId()).orElseThrow(); m.disable(); memberships.saveAndFlush(m);
        status(403,()->service.ingest(member.getId(),event(member,b,now)));
    }
    @Test void retentionRejectsExpiredUploadFiltersBeforeScheduledPurgeAndCannotResurrect() {
        var e=event(member,a,now); service.ingest(member.getId(),e);
        jdbc.update("UPDATE collector_sync_events SET received_at=?",java.sql.Timestamp.from(now.minusSeconds(31*86400L)));
        assertTrue(service.recipients(admin.getId(),org.getId(),x.getId(),5).recipients().isEmpty());
        assertEquals(1,service.purge()); assertEquals(0,service.purge());
        status(410,()->service.ingest(member.getId(),event(member,a,now.minusSeconds(31*86400L))));
    }
    @Test void httpAuthenticationCsrfAndNoMemberReadOrSysadminBypass() throws Exception {
        var mapper=new ObjectMapper();
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String base="http://localhost:"+port+"/api/v1";
        var get=HttpRequest.newBuilder(URI.create(base+"/auth/csrf")).GET().build();
        String csrf=mapper.readTree(client.send(get,HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
        var login=HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf)
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"sync-member@example.org\",\"password\":\"password-test-123\"}")).build();
        assertEquals(200,client.send(login,HttpResponse.BodyHandlers.ofString()).statusCode());
        csrf=mapper.readTree(client.send(get,HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
        String body=mapper.writeValueAsString(event(member,a,now));
        var post=HttpRequest.newBuilder(URI.create(base+"/collector-sync-events")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        assertEquals(403,client.send(post.build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var response=client.send(post.header("X-XSRF-TOKEN",csrf).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(base+"/organizations/"+org.getId()+"/feeding-sites/"+x.getId()+"/sync-recipients")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/organizations/"+org.getId()+"/feeding-sites/"+x.getId()+"/sync-recipients")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
