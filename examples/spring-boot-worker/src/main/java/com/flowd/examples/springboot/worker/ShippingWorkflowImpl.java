package com.flowd.examples.springboot.worker;

import com.flowd.sdk.workflow.ActivityOptions;
import com.flowd.sdk.workflow.Workflow;

import java.time.Duration;

/**
 * Deliberately NOT a {@code @Component} — see {@code
 * FlowdWorkerAutoConfiguration}'s doc for why workflow implementations are
 * classpath-scanned rather than Spring-managed: {@code FlowdWorker}
 * constructs a fresh instance of this class for every execution via its
 * own no-argument constructor, independent of Spring's container. If it
 * needs a real dependency, that dependency belongs on an activity instead
 * (see {@link ShippingActivitiesImpl}), reached through {@link
 * Workflow#newActivityStub}, exactly as {@link ShippingWorkflow} does here.
 *
 * <p>Three independent activity stubs, three separate {@code
 * ScheduleActivityTask}/{@code RespondActivityTaskCompleted} round trips —
 * {@link #ship} just calls them in order. Each stub carries its own {@link
 * ActivityOptions}; inventory and shipping get a tighter timeout than the
 * original 30s default, carrier dispatch keeps it since it has no urgency.
 */
public final class ShippingWorkflowImpl implements ShippingWorkflow {
    private final InventoryActivities inventory = Workflow.newActivityStub(
            InventoryActivities.class,
            new ActivityOptions(null, null, Duration.ofSeconds(10)));

    private final ShippingActivities shipping = Workflow.newActivityStub(
            ShippingActivities.class,
            new ActivityOptions(null, null, Duration.ofSeconds(10)));

    private final CarrierActivities carrier = Workflow.newActivityStub(
            CarrierActivities.class,
            new ActivityOptions(null, null, Duration.ofSeconds(30)));

    @Override
    public String ship(String orderId) {
        inventory.reserveStock(orderId);
        String trackingId = shipping.shipPackage(orderId);
        carrier.dispatchToCarrier(new CarrierActivities.DispatchRequest(orderId, trackingId));
        return trackingId;
    }
}
