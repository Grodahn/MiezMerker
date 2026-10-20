package org.miezmerker.backend;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.admin.AdminOrganizationsController;
import org.miezmerker.backend.admin.AdminService;
import org.miezmerker.backend.admin.SysadminOrganizationService;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.SystemAuthorizationService;
import org.miezmerker.backend.web.MembershipConflictHandler;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("auth")
@Tag("admin")
class MembershipConflictHandlerTest {
    @Test
    void rolledBackSysadminWriteReturnsSafeConflictInsteadOfSuccessRedirect() throws Exception {
        var service = mock(SysadminOrganizationService.class);
        var principal = new AppUserDetails(UUID.randomUUID(), "sysop@example.org", "unused",
                UserStatus.ACTIVE);
        var orgId = UUID.randomUUID();
        when(service.assignAdmin(principal, orgId, "existing@example.org"))
                .thenThrow(new ObjectOptimisticLockingFailureException(
                        OrganizationMembership.class, UUID.randomUUID()));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminOrganizationsController(
                        mock(SystemAuthorizationService.class), mock(AdminService.class), service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new MembershipConflictHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of()));
        try {
            mvc.perform(post("/admin/organizations/" + orgId + "/admins")
                            .param("email", "existing@example.org"))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(containsString("Bitte die Seite neu laden")))
                    .andExpect(content().string(not(containsString("OrganizationMembership"))));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
