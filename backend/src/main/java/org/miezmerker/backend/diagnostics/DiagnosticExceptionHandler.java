package org.miezmerker.backend.diagnostics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class DiagnosticExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception exception, HttpServletRequest request) {
        int status = exception instanceof AccessDeniedException ? 403
                : exception instanceof AuthenticationException ? 401
                : exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        var headers = new HttpHeaders();
        if (exception instanceof ErrorResponse error) headers.putAll(error.getHeaders());
        headers.setCacheControl("no-store");
        var body = ApiError.forStatus(status);
        var handler = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof org.springframework.web.method.HandlerMethod method) {
            String operation = method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
            body = new ApiError(body.status(), body.error(),
                    ErrorCodes.operation(operation, status, body.code()), body.message());
        }
        if (request.getRequestURI().startsWith(request.getContextPath() + "/admin")) {
            headers.setContentType(MediaType.TEXT_HTML);
            return new ResponseEntity<>(body.html(), headers, status);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, status);
    }
}
