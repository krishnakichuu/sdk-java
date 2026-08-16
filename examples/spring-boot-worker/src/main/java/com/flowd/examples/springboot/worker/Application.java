package com.flowd.examples.springboot.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.concurrent.CountDownLatch;

/**
 * The consumer process. {@code flowd.task-queue} is set in
 * application.yml, so {@code flowd-sdk-spring-boot-starter} auto-registers
 * a {@code FlowdWorker} bean that long-polls "shipping-spring", drives
 * {@link ShippingWorkflowImpl}/{@link ShippingActivitiesImpl}, and never
 * initiates anything itself — the Spring Boot analog of {@code
 * examples/plain-java}'s {@code *Worker} mains, run as its own
 * process/pod, exactly as a real deployment would.
 *
 * <p>{@code FlowdWorkerLifecycle} deliberately runs the poll loops on
 * daemon threads (so context startup never blocks on them) — in a normal
 * Spring Boot app, an embedded web server's non-daemon threads are what
 * keeps the JVM alive afterward. This app has no web layer, so {@code
 * main} blocks explicitly instead; Spring's shutdown hook (registered
 * automatically by {@code SpringApplication.run}) still runs {@code
 * FlowdWorkerLifecycle#stop} on Ctrl-C/SIGTERM.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(Application.class, args);
        new CountDownLatch(1).await();
    }
}
