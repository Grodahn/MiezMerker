package org.miezmerker.backend.diagnostics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class DiagnosticExceptionHandler extends ResponseEntityExceptionHandler {
    // Keep Spring's complete status/header mapping for framework failures,
    // including type conversion, malformed bodies, methods and media types.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object ignoredBody,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var servlet = (ServletWebRequest) request;
        if (servlet.getResponse() != null && servlet.getResponse().isCommitted()) return null;
        return render(status.value(), headers, servlet.getRequest());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> failure(Exception exception, HttpServletRequest request) {
        int status = exception instanceof AccessDeniedException ? 403
                : exception instanceof AuthenticationException ? 401
                : exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        var headers = new HttpHeaders();
        if (exception instanceof ErrorResponse error) headers.putAll(error.getHeaders());
        return render(status, headers, request);
    }

    private ResponseEntity<Object> render(int status, HttpHeaders originalHeaders, HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.putAll(originalHeaders);
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
