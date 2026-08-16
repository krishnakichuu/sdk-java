package com.flowd.examples.springboot.client;

import com.flowd.sdk.client.WorkflowClient;
import com.flowd.sdk.client.WorkflowOptions;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The producer process. {@code flowd.task-queue} is unset in
 * application.yml, so {@code FlowdWorkerAutoConfiguration} never
 * activates — this process only ever gets a {@link WorkflowClient} bean
 * from the starter's {@code FlowdClientAutoConfiguration}, never a {@code
 * FlowdWorker}. The {@link CommandLineRunner} below starts one {@code
 * ShippingWorkflow} run on "shipping-spring" and exits — the Spring Boot
 * analog of {@code examples/plain-java}'s {@code *Starter} mains, run as
 * its own process/pod, independent of and never competing with {@code
 * examples/spring-boot-worker} for the same task queue.
 */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner demo(WorkflowClient client) {
        return args -> {
            String orderId = "spring-order-" + System.currentTimeMillis();

            ShippingWorkflow workflow = client.newWorkflowStub(ShippingWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(orderId)
                            .setTaskQueue("shipping-spring")
                            .build());

            System.out.printf("[client] starting ShippingWorkflow for %s%n", orderId);
            String tracking = workflow.ship(orderId);
            System.out.println("[client] shipped: " + tracking);
        };
    }
}
