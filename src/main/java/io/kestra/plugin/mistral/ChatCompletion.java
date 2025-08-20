package io.kestra.plugin.mistral;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
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
                namespace: company.team

                tasks:
                  - id: chat_completion
                    type: io.kestra.plugin.mistral.ChatCompletion
                    apiKey: "{{ secret('MISTRAL_API_KEY') }}"
                    modelName: open-mistral-7b
                    messages:
                      - type: SYSTEM
                        content: You are a helpful assistant, answer concisely, avoid overly casual language or unnecessary verbosity.
                      - type: USER
                        content: "What is the capital of France?"
                """
        ),
        @Example(
            title = "Mistral chat with Structured Output (JSON schema)",
            full = true,
            code = """
                id: mistral_structured
                namespace: company.team

                tasks:
                  - id: chat_completion_structured
                    type: io.kestra.plugin.mistral.ChatCompletion
                    apiKey: "{{ secret('MISTRAL_API_KEY') }}"
                    modelName: "ministral-8b-latest"
                    messages:
                      - type: SYSTEM
                        content: "Extract the books information."
                      - type: USER
                        content: "I recently read 'To Kill a Mockingbird' by Harper Lee."
                    jsonResponseSchema: |
                      {
                        "type": "object",
                        "title": "Book",
                        "additionalProperties": false,
                        "required": ["name", "authors"],
                        "properties": {
                          "name": { "type": "string", "title": "Name" },
                          "authors": { "type": "array", "title": "Authors", "items": { "type": "string" } }
                        }
                      }
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

    @Schema(
        title = "JSON Response Schema",
        description = "JSON schema (as string) to force a custom Structured Output. " +
            "If provided, the request will include response_format = { type: \"json_schema\", json_schema: { schema, name, strict } } " +
            "as described in Mistral documentation."
    )
    private Property<String> jsonResponseSchema;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rModelName = runContext.render(modelName).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse("https://api.mistral.ai/v1");
        var rMessages = runContext.render(messages).asList(ChatMessage.class);
        var rJsonResponseSchema = runContext.render(jsonResponseSchema).as(String.class).orElse(null);

        var formattedMessages = rMessages.stream()
            .map(msg -> Map.of(
                "role", msg.type().role(),
                "content", Objects.toString(msg.content(), "")
            ))
            .toList();

        // Build the request body as mutable in order to add response_format if needed
        var requestBody = new LinkedHashMap<>();
        requestBody.put("model", rModelName);
        requestBody.put("messages", formattedMessages);

        // If a schema is provided, add response_format according to Mistral's spec
        if (rJsonResponseSchema != null) {
            var responseFormat = getJsonNodes(rJsonResponseSchema);
            requestBody.put("response_format", responseFormat);
        }

        try (var client = new HttpClient(runContext, HttpConfiguration.builder().build())) {
            var request = HttpRequest.builder()
                .uri(URI.create(rBaseUrl + "/chat/completions"))
                .addHeader("Authorization", "Bearer " + rApiKey)
                .addHeader("Content-Type", "application/json")
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder().content(requestBody).build())
                .build();

            var response = client.request(request, ObjectNode.class);

            if (response.getStatus().getCode() >= 400) {
                throw new IOException("Mistral API error: " + response.getBody());
            }

            var content = response.getBody()
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText();

            // The message.content returned by the API remains a JSON string; Output is unchanged for compatibility
            return Output.builder()
                .response(content)
                .raw(response.getBody().toString())
                .build();
        }
    }

    private static ObjectNode getJsonNodes(String jsonResponseSchema) throws JsonProcessingException {
        var mapper = new ObjectMapper();
        JsonNode schemaNode = mapper.readTree(jsonResponseSchema); // validate/parse the provided JSON

        // Wrap the provided schema inside the expected API structure:
        // { type: "json_schema", json_schema: { schema, name, strict } }
        ObjectNode jsonSchemaWrapper = mapper.createObjectNode();
        jsonSchemaWrapper.set("schema", schemaNode);
        jsonSchemaWrapper.put("name", "kestra_schema");  // arbitrary name required by the API
        jsonSchemaWrapper.put("strict", true);           // strict = true is recommended

        ObjectNode responseFormat = mapper.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", jsonSchemaWrapper);
        return responseFormat;
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
        ASSISTANT("assistant"),
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
