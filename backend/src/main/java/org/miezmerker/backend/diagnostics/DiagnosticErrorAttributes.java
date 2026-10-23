package org.miezmerker.backend.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/** Servlet/container error fallback; never expose path, exception, binding values or stack. */
@Component
public class DiagnosticErrorAttributes extends DefaultErrorAttributes {
    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        var original = super.getErrorAttributes(request, ErrorAttributeOptions.defaults());
        int status = original.get("status") instanceof Number number ? number.intValue() : 500;
        var error = ApiError.forStatus(status);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", error.status());
        result.put("error", error.error());
        result.put("code", error.code());
        result.put("message", error.message());
        return result;
    }
}
