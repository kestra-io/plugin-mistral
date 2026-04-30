package io.kestra.plugin.mistral;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractMistralConnection extends Task {

    @Schema(title = "API key", description = "Bearer token for the Mistral API; keep in a secret variable.")
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> apiKey;

    @Schema(title = "Base URL", description = "API base URL; defaults to `https://api.mistral.ai/v1`.")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue("https://api.mistral.ai/v1");

    protected ObjectNode executeRequest(RunContext runContext, String method, String path, Object body) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse("https://api.mistral.ai/v1");

        try (var client = new HttpClient(runContext, HttpConfiguration.builder().build())) {
            var requestBuilder = HttpRequest.builder()
                .uri(URI.create(rBaseUrl + path))
                .addHeader("Authorization", "Bearer " + rApiKey)
                .addHeader("Content-Type", "application/json")
                .method(method);

            if (body != null) {
                requestBuilder.body(HttpRequest.JsonRequestBody.builder().content(body).build());
            }

            var response = client.request(requestBuilder.build(), ObjectNode.class);

            if (response.getStatus().getCode() >= 400) {
                throw new IOException("Mistral API error " + response.getStatus().getCode() + ": " + response.getBody());
            }

            return response.getBody();
        }
    }
}
