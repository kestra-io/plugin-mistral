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
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    protected Property<String> apiKey;

    @Schema(title = "Base URL", description = "API base URL; defaults to `https://api.mistral.ai/v1`.")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    protected ObjectNode executeRequest(RunContext runContext, String method, String path, Object body) throws Exception {
        return client(runContext).execute(method, path, body);
    }

    protected Client client(RunContext runContext) throws Exception {
        return client(runContext, HttpConfiguration.builder().build());
    }

    /**
     * Resolves the credentials once. A caller running off the worker thread must reuse the returned client
     * instead of rendering again: every render of a secret mutates the run context's shared, unsynchronized
     * mask list.
     */
    protected Client client(RunContext runContext, HttpConfiguration configuration) throws Exception {
        return new Client(
            runContext,
            runContext.render(apiKey).as(String.class).orElseThrow(),
            runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL),
            configuration
        );
    }

    protected record Client(RunContext runContext, String apiKey, String baseUrl, HttpConfiguration configuration) {

        ObjectNode execute(String method, String path, Object body) throws Exception {
            try (var client = new HttpClient(runContext, configuration)) {
                var requestBuilder = HttpRequest.builder()
                    .uri(URI.create(baseUrl + path))
                    .addHeader("Authorization", "Bearer " + apiKey)
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

        // The record's generated toString would print the API key.
        @Override
        public String toString() {
            return "Client[baseUrl=" + baseUrl + "]";
        }
    }
}
