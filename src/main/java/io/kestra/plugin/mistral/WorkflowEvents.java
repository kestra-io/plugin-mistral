package io.kestra.plugin.mistral;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kestra.core.exceptions.ResourceExpiredException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Trigger a Kestra flow on Mistral Workflow completion events",
            full = true,
            code = """
                id: react_to_mistral_workflow
                namespace: company.team

                triggers:
                  - id: workflow_completed
                    type: io.kestra.plugin.mistral.WorkflowEvents
                    apiKey: "{{ secret('MISTRAL_API_KEY') }}"
                    workflowName: "my-summarization-workflow"
                    eventTypes:
                      - WorkflowExecutionCompleted
                      - WorkflowExecutionFailed
                    interval: PT30S

                tasks:
                  - id: log_event
                    type: io.kestra.core.tasks.log.Log
                    message: "Workflow {{ trigger.workflowExecId }} ended with event {{ trigger.eventType }}"
                """
        )
    }
)
@Schema(
    title = "Trigger on Mistral Workflow events",
    description = """
        Polls the Mistral Workflows event list endpoint on a fixed interval and emits one Kestra execution per matched event.

        **Note**: Mistral Workflows is currently in public preview.

        On the **first poll** (no cursor stored), the trigger fetches the current event page, stores the `next_cursor`, and emits **nothing** — this avoids flooding Kestra with historical events.
        From the second poll onwards, only new events are returned and filtered against `eventTypes` and `workflowName`.
        """
)
public class WorkflowEvents extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<WorkflowEvents.Output> {

    private static final String CURSOR_KV_KEY = "mistral_workflow_events_cursor";

    @Schema(title = "API key", description = "Bearer token for the Mistral API; keep in a secret variable.")
    @PluginProperty(group = "connection", secret = true)
    private Property<String> apiKey;

    @Schema(title = "Base URL", description = "API base URL; defaults to `https://api.mistral.ai/v1`.")
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue("https://api.mistral.ai/v1");

    @Schema(title = "Workflow name filter", description = "Optional workflow name to filter events. When omitted, events from all workflows are considered.")
    @PluginProperty(group = "processing")
    private Property<String> workflowName;

    @Schema(
        title = "Event types filter",
        description = """
            List of event types to emit executions for. Defaults to terminal execution events:
            `WorkflowExecutionCompleted`, `WorkflowExecutionFailed`, `WorkflowExecutionCanceled`.
            Other available types include `WorkflowExecutionStarted` and task-level events.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<List<String>> eventTypes = Property.ofValue(
        List.of("WorkflowExecutionCompleted", "WorkflowExecutionFailed", "WorkflowExecutionCanceled")
    );

    @Schema(title = "Poll interval", description = "How often to poll the Mistral event list endpoint. Defaults to `PT30S`.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofSeconds(30);

    @Override
    public Duration getInterval() {
        return interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse("https://api.mistral.ai/v1");
        var rWorkflowName = runContext.render(workflowName).as(String.class).orElse(null);
        var rEventTypes = runContext.render(eventTypes).asList(String.class);

        var storedCursor = loadCursor(runContext);

        if (storedCursor == null) {
            // Cold start: seed cursor without emitting events
            var seedResponse = fetchEvents(runContext, rApiKey, rBaseUrl, null);
            var nextCursor = seedResponse.path("next_cursor").asText(null);
            if (nextCursor != null) {
                saveCursor(runContext, nextCursor);
            }
            runContext.logger().info("WorkflowEvents trigger initialized; cursor seeded, events will emit from next poll.");
            return Optional.empty();
        }

        var response = fetchEvents(runContext, rApiKey, rBaseUrl, storedCursor);
        var nextCursor = response.path("next_cursor").asText(null);

        if (nextCursor != null && !nextCursor.equals(storedCursor)) {
            saveCursor(runContext, nextCursor);
        }

        var eventsNode = response.path("events");
        if (!eventsNode.isArray() || eventsNode.isEmpty()) {
            return Optional.empty();
        }

        for (var eventNode : eventsNode) {
            var eventType = eventNode.path("event_type").asText(null);
            var wfName = eventNode.path("workflow_name").asText(null);

            if (eventType == null) {
                continue;
            }
            if (!rEventTypes.isEmpty() && !rEventTypes.contains(eventType)) {
                continue;
            }
            if (rWorkflowName != null && !rWorkflowName.equals(wfName)) {
                continue;
            }

            var execId = eventNode.path("workflow_execution_id").asText(null);
            var timestamp = eventNode.path("timestamp").asLong(0L);
            var attributes = eventNode.isObject() ? eventNode.toString() : null;

            var output = Output.builder()
                .workflowExecId(execId)
                .workflowName(wfName)
                .eventType(eventType)
                .eventTimestamp(timestamp)
                .attributes(attributes)
                .build();

            runContext.logger().info("Emitting execution for event {} on workflow execution {}", eventType, execId);

            return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
        }

        return Optional.empty();
    }

    private ObjectNode fetchEvents(RunContext runContext, String rApiKey, String rBaseUrl, String cursor) throws Exception {
        var url = rBaseUrl + "/workflows/events/list";
        if (cursor != null) {
            url = url + "?cursor=" + cursor;
        }

        try (var client = new HttpClient(runContext, HttpConfiguration.builder().build())) {
            var request = HttpRequest.builder()
                .uri(URI.create(url))
                .addHeader("Authorization", "Bearer " + rApiKey)
                .addHeader("Content-Type", "application/json")
                .method("GET")
                .build();

            var response = client.request(request, ObjectNode.class);

            if (response.getStatus().getCode() >= 400) {
                throw new IOException("Mistral API error " + response.getStatus().getCode() + ": " + response.getBody());
            }

            return response.getBody();
        }
    }

    private String loadCursor(RunContext runContext) {
        try {
            KVStore kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
            Optional<KVValue> value = kvStore.getValue(CURSOR_KV_KEY);
            return value.map(v -> v.value().toString()).orElse(null);
        } catch (IOException | ResourceExpiredException e) {
            runContext.logger().warn("Failed to load cursor state: {}", e.getMessage());
            return null;
        }
    }

    private void saveCursor(RunContext runContext, String cursor) {
        try {
            KVStore kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
            kvStore.put(CURSOR_KV_KEY, new KVValueAndMetadata(new KVMetadata(null, (Duration) null), cursor), true);
        } catch (IOException e) {
            runContext.logger().warn("Failed to save cursor state: {}", e.getMessage());
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Workflow execution ID", description = "The ID of the Mistral workflow execution that produced the event.")
        private final String workflowExecId;

        @Schema(title = "Workflow name", description = "The name of the workflow that produced the event.")
        private final String workflowName;

        @Schema(title = "Event type", description = "The event type (e.g. WorkflowExecutionCompleted, WorkflowExecutionFailed).")
        private final String eventType;

        @Schema(title = "Event timestamp", description = "Unix epoch milliseconds when the event was emitted.")
        private final Long eventTimestamp;

        @Schema(title = "Attributes", description = "Raw JSON of the full event payload.")
        private final String attributes;
    }
}
