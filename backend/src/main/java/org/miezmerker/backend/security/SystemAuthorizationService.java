package org.miezmerker.backend.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Global gate only; never substitutes for TenantService membership checks. */
@Service
public class SystemAuthorizationService {
    private final JdbcTemplate jdbc;

    public SystemAuthorizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fresh database decision, including for already authenticated sessions. */
    public boolean isSysadmin(UUID userId) {
        return userId != null && jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_user_system_roles r
                JOIN app_users u ON u.id = r.user_id
                WHERE r.user_id = ? AND r.role = 'SYSADMIN'
                  AND r.enabled = TRUE AND u.status = 'ACTIVE'
                """, Integer.class, userId) == 1;
    }

    public void requireSysadmin(AppUserDetails principal) {
        UUID userId = TenantService.currentUserId(principal);
        if (!isSysadmin(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "system administration required");
        }
    }
}
