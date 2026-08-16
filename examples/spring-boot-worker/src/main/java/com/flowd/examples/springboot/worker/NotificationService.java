package com.flowd.examples.springboot.worker;

import org.springframework.stereotype.Service;

/**
 * An ordinary Spring service bean with nothing flowd-specific about it —
 * stands in for "a real dependency an activity implementation needs" (an
 * email client, a message queue producer, ...). Injected into {@link
 * ShippingActivitiesImpl} the same way it would be injected into any other
 * {@code @Service}/{@code @Component}, proving activity implementations
 * get ordinary Spring DI with zero flowd-specific wiring.
 */
@Service
public class NotificationService {
    public void send(String message) {
        System.out.println("[notification] " + message);
    }
}
