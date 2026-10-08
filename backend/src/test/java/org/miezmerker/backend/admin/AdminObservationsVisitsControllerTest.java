package org.miezmerker.backend.admin;

import org.junit.jupiter.api.Tag;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.ObservationVisitQueryService;
import org.miezmerker.backend.service.VisitAggregationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("admin")
@Tag("visits")
class AdminObservationsVisitsControllerTest {
    AdminService admins = mock(AdminService.class);
    ObservationVisitQueryService queries = mock(ObservationVisitQueryService.class);
    VisitAggregationService aggregation = mock(VisitAggregationService.class);
    AppUserDetails principal = mock(AppUserDetails.class);
    MockHttpSession session = new MockHttpSession();
    UUID userId = UUID.randomUUID(), orgId = UUID.randomUUID();
    AdminObservationsVisitsController controller =
            new AdminObservationsVisitsController(admins, queries, aggregation);

    @BeforeEach
    void activeAdmin() {
        var org = new AdminService.AdminOrg(orgId, "test", "Test");
        when(principal.getId()).thenReturn(userId);
        when(admins.adminOrgs(userId)).thenReturn(List.of(org));
        when(admins.currentOrgOrNull(principal, session)).thenReturn(org);
    }

    @Test
    void overflowingAndPostgresOutOfRangeFiltersReturnNeutralFeedbackBeforeQuerying() {
        for (String route : List.of("/admin/observations", "/admin/visits")) {
            for (String date : List.of("+999999999-01-01T00:00", "-999999999-01-01T00:00",
                    "+294277-01-01T00:00", "1969-01-01T00:00")) {
                for (boolean upper : List.of(false, true)) {
                    var filters = new AdminObservationsVisitsController.Filters();
                    if (upper) filters.setTo(date); else filters.setFrom(date);
                    var model = new ExtendedModelMap();
                    var request = mock(HttpServletRequest.class);
                    when(request.getServletPath()).thenReturn(route);
                    assertEquals(route.substring(1), controller.list(principal, session, model, filters, request));
                    assertEquals("Filter ungültig. Bitte IDs, Zeitraum und Seitengröße prüfen.", model.get("error"));
                    assertTrue(((List<?>) model.get("rows")).isEmpty());
                }
            }
        }
        verifyNoInteractions(queries);
    }

    @Test
    void recomputeDatabaseFailureRedirectsWithNeutralFeedback() {
        when(aggregation.recompute(userId, orgId, null, null))
                .thenThrow(new DataIntegrityViolationException("SQL with SECRETCHIP and private site"));
        var redirect = new RedirectAttributesModelMap();
        assertEquals("redirect:/admin/visits", controller.recompute(principal, session,
                new ExtendedModelMap(), orgId, redirect));
        assertEquals("Neuberechnung nicht möglich. Bitte erneut versuchen.",
                redirect.getFlashAttributes().get("error"));
        assertFalse(redirect.getFlashAttributes().containsKey("success"));
        verify(aggregation).recompute(userId, orgId, null, null);
    }

    @Test
    void unnamedRegisteredCatRemainsDistinctFromUnmappedChip() {
        var org = new Organization("test", "Test", null);
        var site = new FeedingSite(org, "Site", null, null, null, null);
        var cat = new Cat(org, "CHIP", " ", null, null);
        var node = new NodeDevice(UUID.randomUUID(), "x", "y", "fingerprint", "test");
        var raw = new RawObservation(org, node, 1, "CHIP", 1L, "SYNCED", null, null, null, site, null);
        var at = Instant.ofEpochMilli(1);
        var visit = new DerivedVisit(org, site, "CHIP", cat, at, at, 1, "visit-gap-v1", 60, raw, raw);
        when(queries.visits(userId, orgId, null, "", null, null, 51, 0)).thenReturn(List.of(visit));
        var request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn("/admin/visits");
        var model = new ExtendedModelMap();
        controller.list(principal, session, model, new AdminObservationsVisitsController.Filters(), request);
        var row = (AdminObservationsVisitsController.VisitRow) ((List<?>) model.get("rows")).getFirst();
        assertEquals("Katze ohne Namen", row.cat());
        assertEquals("CHIP", row.chip());
    }
}
