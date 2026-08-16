package com.flowd.sdk.spring;

import com.flowd.sdk.activity.ActivityInterface;
import com.flowd.sdk.client.FlowdClient;
import com.flowd.sdk.worker.FlowdWorker;
import com.flowd.sdk.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.List;

/**
 * Auto-registers a {@link FlowdWorker} for {@code flowd.task-queue}, wiring
 * in:
 * <ul>
 *   <li>every {@code @ActivityInterface} implementation already present as
 *   a Spring bean (constructor-injected with whatever else it needs, the
 *   same as any other service bean — activities have no determinism
 *   constraint, so ordinary DI applies unmodified);
 *   <li>every {@code @WorkflowInterface} implementation class found under
 *   {@code flowd.worker.base-packages} (deliberately found by classpath
 *   scan, not as beans — see {@code FlowdWorker.registerWorkflowImplementationTypes}'s
 *   doc for why a workflow implementation is constructed fresh per
 *   execution rather than DI-managed).
 * </ul>
 *
 * <p>Disabled entirely — no {@link FlowdWorker} bean, nothing scanned — if
 * {@code flowd.task-queue} is unset (a client-only application has no use
 * for one) or {@code flowd.worker.enabled=false} is set explicitly.
 */
@AutoConfiguration
@AutoConfigureAfter(FlowdClientAutoConfiguration.class)
@ConditionalOnProperty(prefix = "flowd", name = "task-queue")
public class FlowdWorkerAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(FlowdWorkerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "flowd.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FlowdWorker flowdWorker(FlowdClient client, FlowdProperties props, ApplicationContext applicationContext) {
        FlowdWorker worker = new FlowdWorker(client, props.getTaskQueue(), toWorkerOptions(props.getWorker()));
        registerActivityBeans(worker, applicationContext);
        registerWorkflowImplementationClasses(worker, applicationContext, props.getWorker().getBasePackages());
        return worker;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "flowd.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
    FlowdWorkerLifecycle flowdWorkerLifecycle(FlowdWorker flowdWorker) {
        return new FlowdWorkerLifecycle(flowdWorker);
    }

    private static WorkerOptions toWorkerOptions(FlowdProperties.Worker props) {
        WorkerOptions.Builder b = WorkerOptions.newBuilder();
        if (props.getMaxConcurrentActivities() > 0) {
            b.setMaxConcurrentActivities(props.getMaxConcurrentActivities());
        }
        if (props.getMaxCachedWorkflowExecutions() > 0) {
            b.setMaxCachedWorkflowExecutions(props.getMaxCachedWorkflowExecutions());
        }
        if (props.getStickyScheduleToStartTimeout() != null) {
            b.setStickyScheduleToStartTimeout(props.getStickyScheduleToStartTimeout());
        }
        return b.build();
    }

    /** Every Spring bean whose class implements an {@code @ActivityInterface}, registered as-is. */
    private static void registerActivityBeans(FlowdWorker worker, ApplicationContext ctx) {
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(beanName);
            if (type == null || !implementsActivityInterface(type)) {
                continue;
            }
            Object bean = ctx.getBean(beanName);
            log.info("registering flowd activity implementation bean \"{}\" ({})", beanName, type.getName());
            worker.registerActivitiesImplementations(bean);
        }
    }

    private static boolean implementsActivityInterface(Class<?> type) {
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.isAnnotationPresent(ActivityInterface.class)) {
                return true;
            }
        }
        return false;
    }

    /** Every concrete class implementing an {@code @WorkflowInterface}, found by classpath scan under basePackages. */
    private static void registerWorkflowImplementationClasses(FlowdWorker worker, ApplicationContext ctx, List<String> configuredBasePackages) {
        List<String> basePackages = (configuredBasePackages != null && !configuredBasePackages.isEmpty())
                ? configuredBasePackages
                : AutoConfigurationPackages.has(ctx.getAutowireCapableBeanFactory())
                ? AutoConfigurationPackages.get(ctx.getAutowireCapableBeanFactory())
                : List.of();

        if (basePackages.isEmpty()) {
            log.warn("flowd.worker.base-packages is not set and no @SpringBootApplication package could be "
                    + "inferred — no @WorkflowInterface implementations will be auto-registered; "
                    + "set flowd.worker.base-packages explicitly, or call "
                    + "FlowdWorker.registerWorkflowImplementationTypes(...) yourself");
            return;
        }

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new WorkflowImplementationTypeFilter());
        for (String basePackage : basePackages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> implClass = Class.forName(candidate.getBeanClassName());
                    log.info("registering flowd workflow implementation type {}", implClass.getName());
                    worker.registerWorkflowImplementationTypes(implClass);
                } catch (ClassNotFoundException e) {
                    log.warn("found but could not load workflow implementation class {}", candidate.getBeanClassName(), e);
                }
            }
        }
    }
}
