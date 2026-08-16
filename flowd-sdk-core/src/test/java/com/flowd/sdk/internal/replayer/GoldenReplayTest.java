package com.flowd.sdk.internal.replayer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.JsonDataConverter;
import com.flowd.sdk.workflow.WorkflowContext;
import com.flowd.sdk.worker.ReplayTester;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * flowd's golden-replay safety net, ported: replays a checked-in fixture
 * against this Java port's replay engine and asserts the result matches.
 * The fixture (src/test/resources/replay/simpleworkflow/v1.history.json)
 * is a copy of the one Go's test/replay/replay_test.go uses against
 * sdk/testdata/replay/ — this SDK is now a standalone project (a sibling
 * of flowd-engine, not nested inside it), so it carries its own copy
 * rather than reaching across into another repo's file layout. Still the
 * same real captured history, still the same cross-language proof: one
 * history, two independent implementations, same result.
 *
 * <p>The fixture is written in Go's protojson format (the standard
 * Protobuf JSON mapping), so {@link JsonFormat} parses it directly with no
 * custom mapping code.
 *
 * <p>This lives in core's own test tree (not the {@code examples} module —
 * core must build and test standalone with no dependency on example code),
 * so the fixture's workflow/activity pair is reproduced here as a small
 * test-local fixture ({@link #fixtureWorkflow}) rather than reusing
 * {@code examples/plain-java}'s {@code SimpleWorkflow}/{@code GreetActivity}
 * — same shape, same registered names, deliberately duplicated to keep the
 * module boundary real.
 */
class GoldenReplayTest {

    private static String fixtureWorkflow(WorkflowContext ctx, String name) throws Exception {
        return ctx.executeActivity("GreetActivity", name).get(String.class);
    }

    private static final String GOLDEN_FIXTURE = "/replay/simpleworkflow/v1.history.json";

    private static List<HistoryEvent> loadFixture(String classpathResource) throws Exception {
        String json;
        try (InputStream in = GoldenReplayTest.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found on classpath: " + classpathResource);
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode array = mapper.readTree(json);
        List<HistoryEvent> events = new ArrayList<>();
        for (JsonNode node : array) {
            HistoryEvent.Builder builder = HistoryEvent.newBuilder();
            JsonFormat.parser().merge(node.toString(), builder);
            events.add(builder.build());
        }
        return events;
    }

    @Test
    void replaySimpleWorkflowGolden() throws Exception {
        List<HistoryEvent> events = loadFixture(GOLDEN_FIXTURE);

        Payload result = ReplayTester.replay(events, String.class, GoldenReplayTest::fixtureWorkflow, JsonDataConverter.INSTANCE);
        String got = JsonDataConverter.INSTANCE.fromPayload(result, String.class);

        assertEquals("Hello, ReplayFixture!", got);
    }

    /**
     * Simulates a workflow-code change that is incompatible with an
     * in-flight execution's history: schedules a different activity than
     * GreetActivity, the one recorded at this position in the golden
     * fixture. Mirrors Go's mutatedWorkflow/TestReplayDetectsNonDeterminism.
     */
    private static String mutatedWorkflow(WorkflowContext ctx, String name) throws Exception {
        return ctx.executeActivity("MutatedActivity", name).get(String.class);
    }

    @Test
    void replayDetectsNonDeterminism() throws Exception {
        List<HistoryEvent> events = loadFixture(GOLDEN_FIXTURE);

        try {
            ReplayTester.replay(events, String.class, GoldenReplayTest::mutatedWorkflow, JsonDataConverter.INSTANCE);
            fail("expected a non-determinism error, got none");
        } catch (Exception e) {
            assertTrue(ReplayTester.isNonDeterministic(e),
                    "expected a non-determinism error, got " + e.getClass() + ": " + e.getMessage());
        }
    }
}
