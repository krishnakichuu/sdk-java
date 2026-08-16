package com.flowd.sdk.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicit, optional alternative to relying on Spring Boot's automatic
 * classpath detection: {@link FlowdClientAutoConfiguration} and {@link
 * FlowdWorkerAutoConfiguration} activate on their own the moment {@code
 * flowd-sdk-spring-boot-starter} is a dependency (standard Spring Boot
 * starter behavior — nothing here is required for that). Add this only if
 * you'd rather opt in explicitly (e.g. Spring Boot's own auto-configuration
 * exclusion mechanism is in play elsewhere in the application and you want
 * flowd wired unconditionally), or if you're using this in a plain Spring
 * application context (not Spring Boot) where automatic classpath scanning
 * for auto-configuration doesn't apply.
 *
 * <pre>{@code
 * @EnableFlowd
 * @SpringBootApplication
 * public class Application { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({FlowdClientAutoConfiguration.class, FlowdWorkerAutoConfiguration.class})
public @interface EnableFlowd {
}
