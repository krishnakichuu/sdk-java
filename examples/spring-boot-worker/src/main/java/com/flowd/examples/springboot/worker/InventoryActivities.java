package com.flowd.examples.springboot.worker;

import com.flowd.sdk.activity.ActivityInterface;

@ActivityInterface
public interface InventoryActivities {
    /** Reserves stock for orderId, returns a reservation id. */
    String reserveStock(String orderId);
}
