package com.flowd.examples.orderprocessing;

import com.flowd.sdk.activity.Activity;
import com.flowd.sdk.activity.Info;

/**
 * No {@code ActivityContext} parameter — unlike the lower-level {@code
 * ActivityHandler} API, an annotation-based activity implementation reaches
 * its context through {@link Activity#getExecutionContext()} instead, so
 * its method signature is exactly the interface's: nothing but business
 * parameters.
 */
public final class PaymentActivitiesImpl implements PaymentActivities {
    @Override
    public String chargeCard(String orderId) {
        Info info = Activity.getExecutionContext().getInfo();
        System.out.printf("[worker] charging card for order %s (activityId=%d, attempt=%d)%n",
                orderId, info.activityId(), info.attempt());
        return "receipt-" + orderId;
    }
}
