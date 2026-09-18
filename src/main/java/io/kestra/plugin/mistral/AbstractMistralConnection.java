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

    protected static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";

    @Schema(title = "API key", description = "Bearer token for the Mistral API; keep in a secret variable.")
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> apiKey;

    @Schema(title = "Base URL", description = "API base URL; defaults to `https://api.mistral.ai/v1`.")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    protected ObjectNode executeRequest(RunContext runContext, String method, String path, Object body) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);

        return executeRequest(runContext, rApiKey, rBaseUrl, method, path, body, HttpConfiguration.builder().build());
    }

    /**
     * Takes already-rendered credentials so a caller running off the worker thread never renders a secret
     * Property: every render of a secret mutates the run context's shared, unsynchronized mask list.
     */
    protected ObjectNode executeRequest(
        RunContext runContext,
        String rApiKey,
        String rBaseUrl,
        String method,
        String path,
        Object body,
        HttpConfiguration configuration) throws Exception {
        try (var client = new HttpClient(runContext, configuration)) {
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
