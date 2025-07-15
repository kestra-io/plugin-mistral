package io.kestra.plugin.mistral;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Plugin(
    examples = {
        @Example(
            title = "Basic Mistral chat",
            full = true,
            code = """
                id: mistral
                namespace: company.name

                tasks:
                  - id: chat_completion
                    type: io.kestra.plugin.mistral.ChatCompletion
                    apiKey: '{{ secret("MISTRAL_API_KEY") }}'
                    modelName: open-mistral-7b
                    messages:
                      - type: SYSTEM
                        content: You are a helpful assistant, answer concisely, avoid overly casual language or unnecessary verbosity.
                      - type: USER
                        content: "What is the capital of France?"
                """
        )
    }
)
public class ChatCompletion extends Task implements RunnableTask<ChatCompletion.Output> {

    @Schema(title = "API Key", description = "The Mistral API key used for authentication")
    @NotNull
    private Property<String> apiKey;

    @Schema(title = "Model", description = "The Mistral model to use (e.g. mistral-small, mistral-medium, mistral-large-latest)")
    @NotNull
    private Property<String> modelName;

    @Schema(title = "Base URL", description = "The base URL of the Mistral API")
    @Builder.Default
    private Property<String> baseUrl = Property.ofValue("https://api.mistral.ai/v1");

    @Schema(title = "Messages", description = "List of chat messages in conversational order")
    @NotNull
    private Property<List<ChatMessage>> messages;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var resolvedApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var resolvedModelName = runContext.render(modelName).as(String.class).orElseThrow();
        var resolvedBaseUrl = runContext.render(baseUrl).as(String.class).orElse("https://api.mistral.ai/v1");
        var resolvedMessages = runContext.render(messages).asList(ChatMessage.class);

        var formattedMessages = resolvedMessages.stream()
            .map(msg -> Map.of(
                "role", msg.type().role(),
                "content", Objects.toString(msg.content(), "")
            ))
            .toList();

        var requestBody = Map.of(
            "model", resolvedModelName,
            "messages", formattedMessages
        );

        try (var client = new HttpClient(runContext, HttpConfiguration.builder().build())) {
            var request = HttpRequest.builder()
                .uri(URI.create(resolvedBaseUrl + "/chat/completions"))
                .addHeader("Authorization", "Bearer " + resolvedApiKey)
                .addHeader("Content-Type", "application/json")
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder().content(requestBody).build())
                .build();

            var response = client.request(request, ObjectNode.class);

            if (response.getStatus().getCode() >= 400) {
                throw new IOException("Mistral API error: " + response.getBody());
            }

            var content = response.getBody()
                .get("choices")
                .get(0)
                .get("message")
                .get("content")
                .asText();

            return Output.builder()
                .response(content)
                .raw(response.getBody().toString())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private final String response;
        private final String raw;
    }

    @Builder
    public record ChatMessage(ChatMessageType type, String content) {
    }

    public enum ChatMessageType {
        SYSTEM("system"),
        AI("assistant"),
        USER("user");

        private final String role;

        ChatMessageType(String role) {
            this.role = role;
        }

        public String role() {
            return role;
        }
    }
}
