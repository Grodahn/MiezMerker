package org.miezmerker.backend.diagnostics;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiagnosticOpenApi {
    @Bean
    OpenApiCustomizer diagnosticContract() {
        return api -> {
            if (api.getComponents() == null) api.setComponents(new Components());
            var schema = new ObjectSchema()
                    .addProperty("status", new IntegerSchema())
                    .addProperty("error", new StringSchema())
                    .addProperty("code", new StringSchema().pattern("^MM-[A-Z]+-[0-9]{3}$"))
                    .addProperty("message", new StringSchema());
            schema.setRequired(java.util.List.of("status", "error", "code", "message"));
            api.getComponents().addSchemas("DiagnosticError", schema);
            api.getComponents().addResponses("DiagnosticError", new ApiResponse()
                    .description("Safe diagnostic error. HTTP status and authorization boundaries are unchanged.")
                    .content(new Content().addMediaType("application/json",
                            new io.swagger.v3.oas.models.media.MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/DiagnosticError")))));
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (!operation.getResponses().containsKey("default")) {
                    operation.getResponses().addApiResponse("default",
                            new ApiResponse().$ref("#/components/responses/DiagnosticError"));
                }
            }));
        };
    }
}
