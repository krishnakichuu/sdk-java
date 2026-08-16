package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.Activity;
import com.flowd.sdk.activity.Info;
import org.springframework.stereotype.Component;

/**
 * A {@code @Component} like any other Spring bean — constructor-injected
 * with {@link NotificationService}, discovered and registered with the
 * auto-configured {@code FlowdWorker} by {@code
 * FlowdWorkerAutoConfiguration} because it implements {@link
 * ShippingActivities} (an {@code @ActivityInterface}), with no other
 * flowd-specific annotation needed on this class itself.
 */
@Component
public class ShippingActivitiesImpl implements ShippingActivities {
    private final NotificationService notifications;

    public ShippingActivitiesImpl(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String shipPackage(String orderId) {
        Info info = Activity.getExecutionContext().getInfo();
        notifications.send("shipping order " + orderId + " (activityId=" + info.activityId() + ")");
        return "tracking-" + orderId;
    }
}
