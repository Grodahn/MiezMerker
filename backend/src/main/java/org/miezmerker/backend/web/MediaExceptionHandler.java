package org.miezmerker.backend.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Uniform rejection for uploads the servlet container refuses before the
 * application sees them (#95). The container multipart limits sit just
 * above the application limit so the app normally answers with a
 * controlled 413 itself; anything larger still fails closed here with the
 * same status and no internal details.
 */
@org.springframework.core.annotation.Order(0)
@RestControllerAdvice
public class MediaExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    public org.springframework.http.ResponseEntity<org.miezmerker.backend.diagnostics.ApiError> oversized(MultipartException e) {
        return org.springframework.http.ResponseEntity.status(413).header("Cache-Control", "no-store")
                .body(org.miezmerker.backend.diagnostics.ApiError.forStatus(413));
    }
}
