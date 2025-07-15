package io.kestra.plugin.mistral;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
public class ChatCompletionTest {

    private final String MISTRAL_API_KEY = System.getenv("MISTRAL_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".*")
    @Test
    void shouldGetResultsWithChatCompletion() throws Exception {
        var runContext = runContextFactory.of(Map.of(
            "apiKey", MISTRAL_API_KEY,
            "modelName", "open-mistral-7b",
            "messages", List.of(
                ChatCompletion.ChatMessage.builder()
                    .type(ChatCompletion.ChatMessageType.USER)
                    .content("What is the capital of France? Answer just the name.")
                    .build()
            )
        ));

        var task = ChatCompletion.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .modelName(Property.ofExpression("{{ modelName }}"))
            .messages(Property.ofExpression("{{ messages }}"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getResponse(), notNullValue());
        assertThat(output.getResponse(), containsString("Paris"));
    }
}
