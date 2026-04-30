package io.kestra.plugin.mistral;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class WorkflowEventsTest {

    private final String MISTRAL_API_KEY = System.getenv("MISTRAL_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".*")
    @Test
    void shouldSeedCursorOnFirstPollWithoutEmittingEvents() throws Exception {
        var runContext = runContextFactory.of(
            Map.of("apiKey", MISTRAL_API_KEY)
        );

        var trigger = WorkflowEvents.builder()
            .id("test-workflow-events")
            .type(WorkflowEvents.class.getName())
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .build();

        // Direct HTTP call to verify events list endpoint is reachable and returns valid structure
        // We call the protected fetchEvents equivalent via the full evaluate path.
        // On first poll (no cursor), evaluate must return empty and not throw.
        var result = trigger.evaluate(
            io.kestra.core.models.conditions.ConditionContext.builder()
                .runContext(runContext)
                .build(),
            io.kestra.core.models.triggers.TriggerContext.builder()
                .namespace("company.team")
                .flowId("test-flow")
                .triggerId("test-trigger")
                .build()
        );

        // First poll always returns empty (cold start seeding strategy)
        assertThat(result, is(Optional.empty()));
    }
}
