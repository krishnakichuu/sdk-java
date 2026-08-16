package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.ActivityInterface;

@ActivityInterface
public interface CarrierActivities {
    /**
     * Hands the already-packed shipment to a carrier for pickup.
     *
     * <p>Takes one payload object, not two String parameters — the
     * annotation-based API's activity/workflow methods are single-argument
     * only ({@code FlowdWorker.registerActivityMethod} always reads just
     * {@code parameterTypes()[0]}); a second parameter is silently dropped
     * from the call, which throws a reflection error on every dispatch and
     * (with no bounded activity-retry cap configured here) retries forever.
     */
    void dispatchToCarrier(DispatchRequest request);

    record DispatchRequest(String orderId, String trackingId) {
    }
}
