package com.flowd.sdk.internal.replayer;

import com.flowd.api.v1.ActivityTaskCompletedEventAttributes;
import com.flowd.api.v1.ActivityTaskScheduledEventAttributes;
import com.flowd.api.v1.Command;
import com.flowd.api.v1.HistoryEvent;
import com.flowd.api.v1.HistoryEventType;
import com.flowd.api.v1.Payload;
import com.flowd.api.v1.WorkflowExecutionSignaledEventAttributes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Mirrors sdk/internal/replayer/dispatcher_test.go's Execution-level cases
 * (Go). Go's test pokes Execution's private fields directly (same
 * package); this drives the same scenarios through the real public entry
 * point, loadHistory(), a stricter but equally valid way to set up the same
 * "already recorded in history" state.
 */
class ExecutionTest {

    private static HistoryEvent activityScheduled(long id, String type) {
        return HistoryEvent.newBuilder()
                .setEventId(1)
                .setEventType(HistoryEventType.HISTORY_EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                .setActivityTaskScheduledEventAttributes(
                        ActivityTaskScheduledEventAttributes.newBuilder()
                                .setActivityId(id)
                                .setActivityType(type))
                .build();
    }

    private static HistoryEvent activityCompleted(long id) {
        return HistoryEvent.newBuilder()
                .setEventId(2)
                .setEventType(HistoryEventType.HISTORY_EVENT_TYPE_ACTIVITY_TASK_COMPLETED)
                .setActivityTaskCompletedEventAttributes(
                        ActivityTaskCompletedEventAttributes.newBuilder()
                                .setActivityId(id))
                .build();
    }

    @Test
    void activityFutureResolvesFromHistory() {
        Execution e = new Execution();
        e.loadHistory(List.of(activityScheduled(1, "Foo"), activityCompleted(1)));

        AtomicBoolean resolved = new AtomicBoolean(false);
        e.dispatcher.go(co -> {
            ActivityFuture f = e.scheduleActivity("Foo", null, ActivityOptions.DEFAULT);
            f.get(co);
            resolved.set(true);
        });
        e.dispatcher.executeRound();

        assertTrue(resolved.get(),
                "future should have resolved within one round when its outcome is already in history");
        assertTrue(e.dispatcher.allDone(), "coroutine should have completed, not blocked");
    }

    @Test
    void scheduleActivityDetectsNonDeterminism() {
        Execution e = new Execution();
        e.loadHistory(List.of(activityScheduled(1, "Foo")));

        e.dispatcher.go(co -> {
            // History recorded "Foo" at this position; scheduling "Bar"
            // instead must be caught as non-determinism, not silently accepted.
            e.scheduleActivity("Bar", null, ActivityOptions.DEFAULT);
        });
        e.dispatcher.executeRound();

        Throwable err = e.dispatcher.firstPanic();
        assertNotNull(err, "expected a non-determinism error, got null");
        if (!(err instanceof NonDeterministicError)) {
            fail("expected NonDeterministicError, got " + err.getClass() + ": " + err.getMessage());
        }
    }

    private static Payload payload(String s) {
        return Payload.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8("\"" + s + "\"")).build();
    }

    @Test
    void queryHandlerAnswersFromRegisteredHandler() throws Exception {
        Execution e = new Execution();
        e.setQueryHandler("status", args -> payload("ok"));

        Payload result = e.invokeQueryHandler("status", null);

        assertEquals("\"ok\"", result.getData().toStringUtf8());
    }

    @Test
    void invokeQueryHandlerFailsForUnregisteredType() {
        Execution e = new Execution();
        try {
            e.invokeQueryHandler("missing", null);
            fail("expected an exception for an unregistered query type");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
    }

    @Test
    void continueAsNewSetsContinuedAsNewAndEmitsCommand() {
        Execution e = new Execution();
        e.continueAsNew("NextWorkflow", payload("carry-over"), Execution.ContinueAsNewOptions.DEFAULT);

        assertNotNull(e.continuedAsNew, "continuedAsNew should be set");
        assertEquals("NextWorkflow", e.continuedAsNew.getWorkflowType());
        assertEquals(1, e.newCommands.size());
        assertTrue(e.newCommands.get(0).getCommandCase() == Command.CommandCase.CONTINUE_AS_NEW_WORKFLOW_EXECUTION);
        // complete()/continueAsNew() are mutually exclusive outcomes — result/err must stay unset.
        assertNull(e.result);
        assertNull(e.err);
    }

    @Test
    void resetRoundOutputClearsPreviousRoundButNotScheduledState() {
        Execution e = new Execution();
        e.loadHistory(List.of(activityScheduled(1, "Foo"), activityCompleted(1)));
        e.dispatcher.go(co -> {
            ActivityFuture f = e.scheduleActivity("Foo", null, ActivityOptions.DEFAULT);
            f.get(co);
            e.complete(payload("done"), null);
        });
        e.dispatcher.executeRound();
        assertNotNull(e.result, "sanity: workflow should have completed within one round");

        e.resetRoundOutput();

        assertNull(e.result, "resetRoundOutput must clear result");
        assertNull(e.err, "resetRoundOutput must clear err");
        assertTrue(e.newCommands.isEmpty(), "resetRoundOutput must clear newCommands");
    }

    private static HistoryEvent signaledEvent(String name, Payload input) {
        WorkflowExecutionSignaledEventAttributes.Builder attrs =
                WorkflowExecutionSignaledEventAttributes.newBuilder().setSignalName(name);
        if (input != null) {
            attrs.setInput(input);
        }
        return HistoryEvent.newBuilder()
                .setEventType(HistoryEventType.HISTORY_EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
                .setWorkflowExecutionSignaledEventAttributes(attrs)
                .build();
    }

    // ---- Signal handlers ----

    /**
     * Proves a signal recorded in history before its handler is ever
     * registered — the normal case on a full, non-sticky replay, where
     * loadHistory scans the entire run before workflow code has run at
     * all — is still delivered: buffered, then flushed the instant
     * setSignalHandler registers a matching handler. Mirrors Go's
     * TestSetSignalHandlerDeliversBacklogFromLoadHistory.
     */
    @Test
    void setSignalHandlerDeliversBacklogFromLoadHistory() {
        Execution e = new Execution();
        Payload want = payload("paid");
        e.loadHistory(List.of(signaledEvent("payment", want)));

        AtomicReference<Payload> got = new AtomicReference<>();
        e.setSignalHandler("payment", got::set);

        assertEquals(want, got.get());
    }

    /** Mirrors Go's TestSetSignalHandlerDeliversBacklogInOrder. */
    @Test
    void setSignalHandlerDeliversBacklogInOrder() {
        Execution e = new Execution();
        e.loadHistory(List.of(
                signaledEvent("tick", payload("1")),
                signaledEvent("tick", payload("2")),
                signaledEvent("tick", payload("3"))));

        List<String> got = new ArrayList<>();
        e.setSignalHandler("tick", a -> got.add(a.getData().toStringUtf8()));

        assertEquals(List.of("\"1\"", "\"2\"", "\"3\""), got);
    }

    /**
     * Proves a signal_name this workflow declares no handler for is simply
     * never delivered — durably recorded, observably inert. Mirrors Go's
     * TestUnregisteredSignalStaysPendingHarmlessly.
     */
    @Test
    void unregisteredSignalIsNeverDeliveredToADifferentHandler() {
        Execution e = new Execution();
        e.loadHistory(List.of(signaledEvent("unhandled", null)));

        e.setSignalHandler("other", args -> fail("handler for a different signal_name must not be invoked"));
    }

    /**
     * Mirrors Go's TestSetSignalHandlerLatestRegistrationWins — using
     * loadNewEvents (not loadHistory) since re-registering the same
     * signal_name against an already-running Execution, then delivering
     * via the pump coroutine, is the scenario that actually exercises
     * "latest wins": both registrations happen with nothing pending yet,
     * so only the currently-registered handler ever answers the signal
     * that arrives after.
     */
    @Test
    void setSignalHandlerLatestRegistrationWins() {
        Execution e = new Execution();
        AtomicReference<String> got = new AtomicReference<>();
        e.setSignalHandler("q", a -> got.set("first"));
        e.setSignalHandler("q", a -> got.set("second"));

        e.loadNewEvents(List.of(signaledEvent("q", null)));
        e.dispatcher.executeRound();

        assertEquals("second", got.get());
    }

    /**
     * Proves the sticky-resume path: a handler registered in an earlier
     * round (the common case — the coroutine that calls setSignalHandler
     * is long past that point, parked in yield, and will never call it
     * again) still receives a signal that arrives via loadNewEvents on a
     * later task, delivered by the internal pump coroutine
     * setSignalHandler starts. Mirrors Go's
     * TestSignalDeliveredOnLaterTaskViaLoadNewEvents.
     */
    @Test
    void signalDeliveredOnLaterTaskViaLoadNewEvents() {
        Execution e = new Execution();
        AtomicReference<Payload> got = new AtomicReference<>();
        e.dispatcher.go(co -> {
            e.setSignalHandler("payment", got::set);
            while (true) {
                co.yield();
            }
        });
        e.dispatcher.executeRound(); // round 1: registers the handler, no backlog yet

        assertNull(got.get(), "should be nothing before any signal arrived");

        Payload want = payload("paid");
        e.loadNewEvents(List.of(signaledEvent("payment", want)));
        e.dispatcher.executeRound(); // round 2: the pump coroutine, now present, delivers it

        assertEquals(want, got.get());
        assertNull(e.dispatcher.firstPanic());
    }

    /**
     * Proves a delivery panic (e.g. a malformed payload in
     * WorkflowContext.setSignalHandler's decode step) is caught by the
     * same Dispatcher.firstPanic mechanism every other workflow panic goes
     * through. Mirrors Go's TestSignalHandlerPanicSurfacesViaFirstPanic.
     */
    @Test
    void signalHandlerPanicSurfacesViaFirstPanic() {
        Execution e = new Execution();
        e.dispatcher.go(co -> {
            e.setSignalHandler("boom", args -> {
                throw new RuntimeException("bad payload");
            });
            while (true) {
                co.yield();
            }
        });
        e.dispatcher.executeRound();

        e.loadNewEvents(List.of(signaledEvent("boom", null)));
        e.dispatcher.executeRound();

        assertNotNull(e.dispatcher.firstPanic(), "expected firstPanic to report the signal handler's exception");
    }
}
