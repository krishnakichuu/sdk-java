package com.flowd.sdk.spring;

import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.client.WorkflowClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Provides {@link FlowdClient} and {@link WorkflowClient} beans from {@link
 * FlowdProperties} — active as soon as {@code flowd-sdk-spring-boot-starter}
 * is on the classpath (via {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}),
 * no {@code @EnableFlowd} required, the same "just add the dependency"
 * convention every other Spring Boot starter follows. {@code
 * flowd-sdk-core} itself has no idea Spring exists — this class, and this
 * module, are the entire integration.
 *
 * <p>Either bean can be overridden by defining your own of the same type
 * (both are {@code @ConditionalOnMissingBean}) — e.g. to dial with TLS/an
 * API key, which {@link FlowdProperties} doesn't expose a shortcut for.
 */
@AutoConfiguration
@EnableConfigurationProperties(FlowdProperties.class)
public class FlowdClientAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public FlowdClient flowdClient(FlowdProperties props) {
        FlowdClient.Options options = new FlowdClient.Options();
        options.namespace = props.getNamespace();
        return FlowdClient.dial(props.getAddress(), options);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowClient workflowClient(FlowdClient flowdClient) {
        return WorkflowClient.newInstance(flowdClient);
    }
}
