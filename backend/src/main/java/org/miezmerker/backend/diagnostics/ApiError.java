package org.miezmerker.backend.diagnostics;

import org.springframework.http.HttpStatus;

/** Public, deliberately data-free diagnostic envelope; status semantics are unchanged. */
public record ApiError(int status, String error, String code, String message) {
    public static ApiError forStatus(int status) {
        String code = switch (status) {
            case 400 -> ErrorCodes.HTTP_BAD_REQUEST;
            case 401 -> ErrorCodes.HTTP_UNAUTHORIZED;
            case 403 -> ErrorCodes.HTTP_FORBIDDEN;
            case 404 -> ErrorCodes.HTTP_NOT_FOUND;
            case 405 -> ErrorCodes.HTTP_METHOD;
            case 409 -> ErrorCodes.HTTP_CONFLICT;
            case 413 -> ErrorCodes.HTTP_TOO_LARGE;
            case 415 -> ErrorCodes.HTTP_MEDIA;
            case 429 -> ErrorCodes.HTTP_RATE;
            case 503 -> ErrorCodes.HTTP_UNAVAILABLE;
            default -> ErrorCodes.HTTP_UNKNOWN;
        };
        var known = HttpStatus.resolve(status);
        String message = known == null ? "Request failed" : known.getReasonPhrase();
        return new ApiError(status, message, code, message);
    }

    public String html() {
        // All values originate in the status catalog, never the request/exception.
        return "<!doctype html><html lang=\"de\"><meta charset=\"utf-8\">"
                + "<title>Vorgang nicht abgeschlossen</title><main role=\"alert\"><h1>"
                + message + "</h1><p><code>[" + code + "]</code></p>"
                + "<p>Bitte Sitzung, Berechtigungen und Eingaben prüfen und erneut versuchen.</p></main></html>";
    }
}
