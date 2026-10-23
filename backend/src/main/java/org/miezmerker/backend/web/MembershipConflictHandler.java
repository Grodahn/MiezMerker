package org.miezmerker.backend.web;

import org.miezmerker.backend.admin.AdminMembersController;
import org.miezmerker.backend.admin.AdminOrganizationsController;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A conflicting transaction has rolled back before this response is rendered. */
@org.springframework.core.annotation.Order(0)
@RestControllerAdvice(assignableTypes = {MemberController.class, AdminMembersController.class,
        AdminOrganizationsController.class})
public class MembershipConflictHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<?> conflict(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).header("Cache-Control", "no-store")
                    .body(new org.miezmerker.backend.diagnostics.ApiError(409, "Conflict",
                            org.miezmerker.backend.diagnostics.ErrorCodes.ADMIN_CONFLICT,
                            "Die Mitgliedschaft wurde gleichzeitig geändert. Bitte die Seite neu laden."));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Cache-Control", "no-store")
                .body("Die Mitgliedschaft wurde gleichzeitig geändert. Bitte die Seite neu laden "
                        + "und die aktuellen Berechtigungen vor dem erneuten Speichern prüfen. ["
                        + org.miezmerker.backend.diagnostics.ErrorCodes.ADMIN_CONFLICT + "]");
    }
}
