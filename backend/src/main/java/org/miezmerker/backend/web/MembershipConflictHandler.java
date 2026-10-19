package org.miezmerker.backend.web;

import org.miezmerker.backend.admin.AdminMembersController;
import org.miezmerker.backend.admin.AdminOrganizationsController;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A conflicting transaction has rolled back before this response is rendered. */
@RestControllerAdvice(assignableTypes = {MemberController.class, AdminMembersController.class,
        AdminOrganizationsController.class})
public class MembershipConflictHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Cache-Control", "no-store")
                .body("Die Mitgliedschaft wurde gleichzeitig geändert. Bitte die Seite neu laden "
                        + "und die aktuellen Berechtigungen vor dem erneuten Speichern prüfen.");
    }
}
