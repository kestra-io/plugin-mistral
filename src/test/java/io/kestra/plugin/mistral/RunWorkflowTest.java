package io.kestra.plugin.mistral;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class RunWorkflowTest {

    private final String MISTRAL_API_KEY = System.getenv("MISTRAL_API_KEY");
    private final String MISTRAL_TEST_WORKFLOW_ID = System.getenv("MISTRAL_TEST_WORKFLOW_ID");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".*")
    @EnabledIfEnvironmentVariable(named = "MISTRAL_TEST_WORKFLOW_ID", matches = ".*")
    @Test
    void shouldCompleteWorkflowSynchronously() throws Exception {
        var runContext = runContextFactory.of(
            Map.of(
                "apiKey", MISTRAL_API_KEY,
                "workflowId", MISTRAL_TEST_WORKFLOW_ID
            )
        );

        var task = RunWorkflow.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .workflowIdentifier(Property.ofExpression("{{ workflowId }}"))
            .wait(Property.ofValue(true))
            .build();

        var output = task.run(runContext);

        assertThat(output.getExecutionId(), notNullValue());
        assertThat(output.getStatus(), is("COMPLETED"));
    }

    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".*")
    @EnabledIfEnvironmentVariable(named = "MISTRAL_TEST_WORKFLOW_ID", matches = ".*")
    @Test
    void shouldReturnExecutionIdWhenFireAndForget() throws Exception {
        var runContext = runContextFactory.of(
            Map.of(
                "apiKey", MISTRAL_API_KEY,
                "workflowId", MISTRAL_TEST_WORKFLOW_ID
            )
        );

        var task = RunWorkflow.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .workflowIdentifier(Property.ofExpression("{{ workflowId }}"))
            .wait(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getExecutionId(), notNullValue());
        assertThat(output.getStatus(), is("RUNNING"));
    }
}
