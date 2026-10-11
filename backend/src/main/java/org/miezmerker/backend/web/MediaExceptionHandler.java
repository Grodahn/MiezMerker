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
@RestControllerAdvice
public class MediaExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    public void oversized(MultipartException e) {
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "photo exceeds the configured maximum upload size");
    }
}
