package io.kestra.plugin.mistral;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class RunWorkflowTest {

    private static final String EXEC_ID = "exec-0000";

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

    @Test
    void shouldNotStartTheWorkflowWhenKilledBeforeRun() throws Exception {
        var stub = startStub(Duration.ZERO);

        try {
            var task = task(stub, Duration.ofSeconds(1), true);
            task.kill();

            assertThrows(KilledException.class, () -> task.run(runContextFactory.of(Map.of())));
            assertThat(stub.requests(), is(empty()));
        } finally {
            stub.close();
        }
    }

    @Test
    void shouldStopPollingAndCancelTheExecutionOnKill() throws Exception {
        var stub = startStub(Duration.ZERO);

        try {
            // A poll interval far longer than the test timeout: passing proves the wait is interruptible.
            var task = task(stub, Duration.ofSeconds(30), true);
            var outcome = runAsync(task);

            assertThat(stub.statusPolled().await(10, TimeUnit.SECONDS), is(true));

            var startedAt = System.nanoTime();
            task.kill();
            var thrown = outcome.get(10, TimeUnit.SECONDS);
            var elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(thrown, instanceOf(KilledException.class));
            assertThat(elapsedMs, lessThan(30_000L));

            assertThat(stub.cancelReceived().await(10, TimeUnit.SECONDS), is(true));
            assertThat(stub.cancelCount(), is(1L));

            task.kill();
            task.stop();
            assertThat(stub.cancelCount(), is(1L));
        } finally {
            stub.close();
        }
    }

    @Test
    void shouldNotBlockOnKillWhenTheCancelEndpointIsSlow() throws Exception {
        var stub = startStub(Duration.ofSeconds(3));

        try {
            var task = task(stub, Duration.ofSeconds(30), true);
            var outcome = runAsync(task);

            assertThat(stub.statusPolled().await(10, TimeUnit.SECONDS), is(true));

            var startedAt = System.nanoTime();
            task.kill();
            var killElapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(killElapsedMs, lessThan(1_000L));
            assertThat(stub.cancelReceived().await(10, TimeUnit.SECONDS), is(true));
            assertThat(outcome.get(10, TimeUnit.SECONDS), instanceOf(KilledException.class));
        } finally {
            stub.close();
        }
    }

    @Test
    void shouldLeaveTheExecutionRunningOnWorkerShutdown() throws Exception {
        var stub = startStub(Duration.ZERO);

        try {
            var task = task(stub, Duration.ofSeconds(30), true);
            var outcome = runAsync(task);

            assertThat(stub.statusPolled().await(10, TimeUnit.SECONDS), is(true));

            var startedAt = System.nanoTime();
            task.stop();
            var thrown = outcome.get(10, TimeUnit.SECONDS);
            var elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertThat(elapsedMs, lessThan(30_000L));
            assertThat(thrown, is(notNullValue()));
            assertThat(thrown, not(instanceOf(KilledException.class)));
            assertThat(stub.cancelCount(), is(0L));
        } finally {
            stub.close();
        }
    }

    @Test
    void shouldCancelTheExecutionWhenKilledWhileItIsBeingStarted() throws Exception {
        var stub = startStub(Duration.ZERO, true);

        try {
            var task = task(stub, Duration.ofSeconds(30), true);
            var outcome = runAsync(task);

            assertThat(stub.executeReceived().await(10, TimeUnit.SECONDS), is(true));

            // The execution id is not known yet, so this kill finds nothing to cancel and run() has to
            // dispatch the cancel itself once the id lands.
            task.kill();
            stub.executeGate().countDown();

            assertThat(outcome.get(10, TimeUnit.SECONDS), instanceOf(KilledException.class));
            assertThat(stub.cancelReceived().await(10, TimeUnit.SECONDS), is(true));
            assertThat(stub.cancelCount(), is(1L));
        } finally {
            stub.close();
        }
    }

    @Test
    void shouldLeaveADetachedExecutionRunningOnKill() throws Exception {
        var stub = startStub(Duration.ZERO);

        try {
            var task = task(stub, Duration.ofSeconds(1), false);

            var output = task.run(runContextFactory.of(Map.of()));
            assertThat(output.getExecutionId(), is(EXEC_ID));
            assertThat(output.getStatus(), is("RUNNING"));

            task.kill();
            assertThat(stub.cancelCount(), is(0L));
        } finally {
            stub.close();
        }
    }

    private RunWorkflow task(Stub stub, Duration pollInterval, boolean wait) {
        return RunWorkflow.builder()
            .apiKey(Property.ofValue("test-api-key"))
            .baseUrl(Property.ofValue(stub.baseUrl()))
            .workflowIdentifier(Property.ofValue("test-workflow"))
            .wait(Property.ofValue(wait))
            .pollInterval(Property.ofValue(pollInterval))
            .waitTimeout(Property.ofValue(Duration.ofMinutes(5)))
            .build();
    }

    private CompletableFuture<Throwable> runAsync(RunWorkflow task) {
        var runContext = runContextFactory.of(Map.of());

        return CompletableFuture.supplyAsync(() ->
        {
            try {
                task.run(runContext);
                return null;
            } catch (Throwable e) {
                return e;
            }
        });
    }

    private Stub startStub(Duration cancelDelay) throws IOException {
        return startStub(cancelDelay, false);
    }

    private Stub startStub(Duration cancelDelay, boolean gateExecute) throws IOException {
        var requests = new CopyOnWriteArrayList<String>();
        var statusPolled = new CountDownLatch(1);
        var cancelReceived = new CountDownLatch(1);
        var cancelCompleted = new CountDownLatch(1);
        var executeReceived = new CountDownLatch(1);
        var executeGate = new CountDownLatch(gateExecute ? 1 : 0);

        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/workflows", exchange ->
        {
            var path = exchange.getRequestURI().getPath();
            var isCancel = path.endsWith("/cancel");
            requests.add(exchange.getRequestMethod() + " " + path);

            String body;
            if (path.endsWith("/execute")) {
                executeReceived.countDown();
                awaitQuietly(executeGate);
                body = "{\"execution_id\":\"" + EXEC_ID + "\"}";
            } else if (isCancel) {
                // Counted down before the artificial delay so a test can assert the request landed
                // without waiting out the response.
                cancelReceived.countDown();
                sleepQuietly(cancelDelay);
                body = "{}";
            } else {
                statusPolled.countDown();
                body = "{\"status\":\"RUNNING\"}";
            }

            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }

            if (isCancel) {
                cancelCompleted.countDown();
            }
        });
        server.start();

        return new Stub(server, requests, statusPolled, cancelReceived, cancelCompleted, executeReceived, executeGate);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }

        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Stub(
        HttpServer server,
        List<String> requests,
        CountDownLatch statusPolled,
        CountDownLatch cancelReceived,
        CountDownLatch cancelCompleted,
        CountDownLatch executeReceived,
        CountDownLatch executeGate) {
        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1";
        }

        long cancelCount() {
            return requests.stream().filter(request -> request.endsWith("/cancel")).count();
        }

        void close() throws InterruptedException {
            // Lets an in-flight cancel response finish so the server is not torn down mid-response.
            if (cancelCount() > 0) {
                cancelCompleted.await(10, TimeUnit.SECONDS);
            }

            server.stop(0);
        }
    }
}
