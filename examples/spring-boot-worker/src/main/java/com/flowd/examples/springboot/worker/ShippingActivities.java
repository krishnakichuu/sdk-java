package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.ActivityInterface;

@ActivityInterface
public interface ShippingActivities {
    String shipPackage(String orderId);
}
