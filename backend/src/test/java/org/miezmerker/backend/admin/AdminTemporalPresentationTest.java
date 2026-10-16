package org.miezmerker.backend.admin;

import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.TemporalProjectionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("admin") @Tag("visits") @Tag("auth")
class AdminTemporalPresentationTest {
    TemporalProjectionService projections = mock(TemporalProjectionService.class);
    FeedingSiteRepository sites = mock(FeedingSiteRepository.class);
    TenantService tenants = mock(TenantService.class);
    AdminTemporalPresentation presentation = new AdminTemporalPresentation(projections, sites, tenants);
    UUID user = UUID.randomUUID(), org = UUID.randomUUID();

    @Test void groupsRowsOncePerNodeInStableLockOrder() {
        var a = mock(NodeDevice.class); var b = mock(NodeDevice.class);
        var aid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var bid = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        when(a.getNodeId()).thenReturn(aid); when(b.getNodeId()).thenReturn(bid);
        var first = mock(RawObservation.class); var second = mock(RawObservation.class);
        var third = mock(RawObservation.class);
        when(first.getNode()).thenReturn(b); when(second.getNode()).thenReturn(a); when(third.getNode()).thenReturn(b);
        when(projections.projectRows(user,org,aid,List.of(second))).thenReturn(List.of());
        when(projections.projectRows(user,org,bid,List.of(first,third))).thenReturn(List.of());
        assertTrue(presentation.projectPage(user,org,List.of(first,second,third)).isEmpty());
        var order = inOrder(tenants, projections);
        order.verify(tenants).requireAdmin(user, org);
        order.verify(projections).projectRows(user,org,aid,List.of(second));
        order.verify(projections).projectRows(user,org,bid,List.of(first,third));
        verifyNoMoreInteractions(projections);
    }

    @Test void namesUseOneBoundedTenantQueryAndNoCurrentNodeLookup() {
        UUID id = UUID.randomUUID();
        when(sites.findByOrganizationIdAndIdIn(org,List.of(id))).thenReturn(List.of());
        assertTrue(presentation.siteNames(user,org,Arrays.asList(id,null,id)).isEmpty());
        verify(tenants).requireAdmin(user,org);
        verify(sites).findByOrganizationIdAndIdIn(org,List.of(id));
        verifyNoMoreInteractions(sites);
        verifyNoInteractions(projections);
    }

    @Test void estimatedBoundsRoundOutwardsAndStrictBoundsKeepTheirPrecision() {
        long t = java.time.Instant.parse("2026-10-16T07:55:30.123Z").toEpochMilli();
        assertEquals("2026-10-16 07:55 UTC", AdminTemporalPresentation.utc(t,true,false));
        assertEquals("2026-10-16 07:56 UTC", AdminTemporalPresentation.utc(t,true,true));
        assertEquals("2026-10-16T07:55:30.123Z", AdminTemporalPresentation.utc(t,false,true));
        assertNull(AdminTemporalPresentation.utc(null,true,false));
        long exact = java.time.Instant.parse("2026-10-16T07:56:00Z").toEpochMilli();
        assertEquals("2026-10-16 07:56 UTC", AdminTemporalPresentation.utc(exact,true,true));
    }

    @Test void revokedAdminCannotProjectOrResolveNames() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(tenants).requireAdmin(user, org);
        assertThrows(ResponseStatusException.class,
                () -> presentation.projectPage(user, org, List.of()));
        assertThrows(ResponseStatusException.class,
                () -> presentation.siteNames(user, org, List.of(UUID.randomUUID())));
        verifyNoInteractions(projections, sites);
    }
}
