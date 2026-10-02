package org.miezmerker.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
    private final BuildProperties build;

    public SystemController(BuildProperties build) { this.build = build; }

    @Schema(name = "HealthResponse")
    public record HealthResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status) {}

    @Schema(name = "VersionResponse")
    public record VersionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String apiVersion) {}

    @GetMapping(value = "/api/v1/health", produces = "application/json")
    @Operation(operationId = "getHealth", summary = "Application liveness (database initialized at startup)")
    public HealthResponse health() { return new HealthResponse("UP"); }

    @GetMapping(value = "/api/v1/version", produces = "application/json")
    @Operation(operationId = "getVersion", summary = "Build and HTTP API version")
    public VersionResponse version() { return new VersionResponse(build.getVersion(), "v1"); }
}
