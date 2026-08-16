package com.flowd.sdk.activity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java interface as a flowd activity definition. Every method
 * (whether or not individually annotated) is registered as an activity;
 * {@link ActivityMethod} is only needed to override the registered name.
 *
 * <pre>{@code
 * @ActivityInterface
 * public interface PaymentActivities {
 *     String chargeCard(ChargeRequest request);
 *     void refund(String chargeId);
 * }
 * }</pre>
 *
 * <p>An implementation class implements this interface directly and may
 * take any dependencies it needs through its own constructor — activities
 * have no determinism constraint (see {@link ActivityContext}), so
 * ordinary dependency injection works exactly as it would for any other
 * Java class (see {@code Worker.registerActivitiesImplementations}, which
 * takes already-constructed instances for exactly this reason).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActivityInterface {
}
