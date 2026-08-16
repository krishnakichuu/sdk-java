package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.Activity;
import com.flowd.sdk.activity.Info;
import org.springframework.stereotype.Component;

/**
 * A second, independent {@code @ActivityInterface} implementation —
 * registered with the same {@code FlowdWorker} as {@link
 * ShippingActivitiesImpl}, with its own dependencies and its own
 * determinism-free execution. Nothing about adding this required touching
 * {@link ShippingActivities} or its implementation.
 */
@Component
public class InventoryActivitiesImpl implements InventoryActivities {
    @Override
    public String reserveStock(String orderId) {
        Info info = Activity.getExecutionContext().getInfo();
        String reservationId = "reservation-" + orderId;
        System.out.println("[inventory] reserved stock for " + orderId
                + " (activityId=" + info.activityId() + ") -> " + reservationId);
        return reservationId;
    }
}
