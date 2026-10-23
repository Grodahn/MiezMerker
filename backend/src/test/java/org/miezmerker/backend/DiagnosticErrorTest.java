package org.miezmerker.backend;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.diagnostics.DiagnosticExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@Tag("auth")
@Tag("admin")
@Tag("system")
class DiagnosticErrorTest {
    @Test void servletFallbackIgnoresRequestedStacksAndRequestDetails() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/private/tenant/chip");
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("secret token"));
        var attributes = new org.miezmerker.backend.diagnostics.DiagnosticErrorAttributes()
                .getErrorAttributes(new org.springframework.web.context.request.ServletWebRequest(request),
                        org.springframework.boot.web.error.ErrorAttributeOptions.of(
                                org.springframework.boot.web.error.ErrorAttributeOptions.Include.STACK_TRACE,
                                org.springframework.boot.web.error.ErrorAttributeOptions.Include.MESSAGE));
        org.junit.jupiter.api.Assertions.assertEquals("MM-BACKEND-404", attributes.get("code"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("status", "error", "code", "message"),
                attributes.keySet());
        org.junit.jupiter.api.Assertions.assertFalse(attributes.toString().contains("secret"));
        org.junit.jupiter.api.Assertions.assertFalse(attributes.toString().contains("tenant"));
    }
    @RestController
    static class Failures {
        @GetMapping("/api/test/denied/{id}") void denied() {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "private tenant and chip value");
        }
        @GetMapping("/api/test/invalid") void invalid() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "private request payload");
        }
        @GetMapping("/api/test/unknown") void unknown() {
            throw new IllegalStateException("secret token and stack");
        }
        @GetMapping("/admin/test/denied") void admin() {
            throw new AccessDeniedException("private membership");
        }
    }

    @RestController
    static class NodeController {
        @GetMapping("/api/test/claim") void claim() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "private claim proof");
        }
    }
    @Test void knownOperationGetsStableCaseCodeWithoutParsingExceptionText() throws Exception {
        mvc.perform(get("/api/test/claim")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MM-API-001"))
                .andExpect(content().string(not(containsString("claim proof"))));
    }
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failures(), new NodeController())
            .setControllerAdvice(new DiagnosticExceptionHandler()).build();

    @Test void tenantDenialsAreNeutralAndKeepStatus() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/test/denied/" + UUID.randomUUID()))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("MM-BACKEND-403"))
                    .andExpect(jsonPath("$.message").value("Forbidden"))
                    .andExpect(content().string(not(containsString("private"))));
        }
    }
    @Test void badRequestAndUnexpectedExceptionsAreSafeStructuredFailures() throws Exception {
        mvc.perform(get("/api/test/invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MM-BACKEND-400"));
        mvc.perform(get("/api/test/unknown")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("MM-BACKEND-500"))
                .andExpect(content().string(not(containsString("secret"))));
    }
    @Test void adminExceptionRendersReadableHtmlWithCode() throws Exception {
        mvc.perform(get("/admin/test/denied")).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("[MM-BACKEND-403]")))
                .andExpect(content().string(not(containsString("private membership"))));
    }
}
