package com.flowd.examples.orderprocessing;

import com.flowd.sdk.activity.ActivityInterface;

@ActivityInterface
public interface PaymentActivities {
    String chargeCard(String orderId);
}
