package org.miezmerker.backend.diagnostics;

@org.springframework.stereotype.Component("diagnostics")
public class DiagnosticTemplateCodes {
    public String form() { return ErrorCodes.ADMIN_FORM; }
    public String login() { return ErrorCodes.AUTH_LOGIN; }
    public String denied() { return ErrorCodes.AUTH_DENIED; }
    public String session() { return ErrorCodes.AUTH_SESSION; }
    public String photo() { return ErrorCodes.PHOTO_FAILED; }
}
