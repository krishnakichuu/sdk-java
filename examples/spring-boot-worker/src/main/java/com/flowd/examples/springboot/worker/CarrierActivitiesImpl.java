package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.Activity;
import com.flowd.sdk.activity.Info;
import org.springframework.stereotype.Component;

/** A third activity implementation, injected with the same {@link NotificationService} as {@link ShippingActivitiesImpl}. */
@Component
public class CarrierActivitiesImpl implements CarrierActivities {
    private final NotificationService notifications;

    public CarrierActivitiesImpl(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void dispatchToCarrier(DispatchRequest request) {
        Info info = Activity.getExecutionContext().getInfo();
        notifications.send("carrier picked up " + request.orderId() + " (" + request.trackingId()
                + ", activityId=" + info.activityId() + ")");
    }
}
