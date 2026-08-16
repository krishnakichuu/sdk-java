package com.flowd.sdk.internal.registry;

import com.flowd.sdk.activity.ActivityInterface;
import com.flowd.sdk.activity.ActivityMethod;
import com.flowd.sdk.workflow.QueryMethod;
import com.flowd.sdk.workflow.SignalMethod;
import com.flowd.sdk.workflow.WorkflowInterface;
import com.flowd.sdk.workflow.WorkflowMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection over the annotation-based API's marker/method annotations —
 * shared by {@code com.flowd.sdk.workflow.Workflow} (activity stubs,
 * continue-as-new), {@code com.flowd.sdk.worker.Worker} (registration), and
 * {@code com.flowd.sdk.client.WorkflowClient} (typed workflow stubs), so
 * all three agree on the same wire names for the same interface without
 * duplicating the scanning logic three times. SDK-internal.
 */
public final class AnnotationSupport {
    private AnnotationSupport() {
    }

    /** The single {@code @WorkflowMethod}-annotated method of workflowInterface. */
    public static Method requireWorkflowMethod(Class<?> workflowInterface) {
        if (!workflowInterface.isInterface()) {
            throw new IllegalArgumentException(workflowInterface.getName() + " is not an interface");
        }
        if (!workflowInterface.isAnnotationPresent(WorkflowInterface.class)) {
            throw new IllegalArgumentException(workflowInterface.getName() + " is not annotated @WorkflowInterface");
        }
        Method found = null;
        for (Method m : workflowInterface.getMethods()) {
            if (m.isAnnotationPresent(WorkflowMethod.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            workflowInterface.getName() + " declares more than one @WorkflowMethod ("
                                    + found.getName() + ", " + m.getName() + ") — exactly one is required");
                }
                found = m;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(workflowInterface.getName() + " declares no @WorkflowMethod");
        }
        return found;
    }

    /** The registered workflow_type: the @WorkflowMethod's name(), or the interface's simple name if blank. */
    public static String workflowTypeName(Class<?> workflowInterface) {
        Method m = requireWorkflowMethod(workflowInterface);
        String name = m.getAnnotation(WorkflowMethod.class).name();
        return name.isBlank() ? workflowInterface.getSimpleName() : name;
    }

    /** The registered activity_type: the @ActivityMethod's name() if present and non-blank, else the method's own name. */
    public static String activityTypeName(Method m) {
        ActivityMethod ann = m.getAnnotation(ActivityMethod.class);
        if (ann != null && !ann.name().isBlank()) {
            return ann.name();
        }
        return m.getName();
    }

    /** The registered query_type: the @QueryMethod's name() if present and non-blank, else the method's own name. */
    public static String queryTypeName(Method m) {
        QueryMethod ann = m.getAnnotation(QueryMethod.class);
        if (ann != null && !ann.name().isBlank()) {
            return ann.name();
        }
        return m.getName();
    }

    /** The registered signal_name — see {@link SignalMethod}'s doc for why this is declared but not yet dispatched. */
    public static String signalName(Method m) {
        SignalMethod ann = m.getAnnotation(SignalMethod.class);
        if (ann != null && !ann.name().isBlank()) {
            return ann.name();
        }
        return m.getName();
    }

    public static List<Method> queryMethods(Class<?> workflowInterface) {
        List<Method> out = new ArrayList<>();
        for (Method m : workflowInterface.getMethods()) {
            if (m.isAnnotationPresent(QueryMethod.class)) {
                out.add(m);
            }
        }
        return out;
    }

    public static List<Method> signalMethods(Class<?> workflowInterface) {
        List<Method> out = new ArrayList<>();
        for (Method m : workflowInterface.getMethods()) {
            if (m.isAnnotationPresent(SignalMethod.class)) {
                out.add(m);
            }
        }
        return out;
    }

    /** The single {@code @WorkflowInterface} implClass implements. */
    public static Class<?> workflowInterfaceOf(Class<?> implClass) {
        Class<?> found = null;
        for (Class<?> iface : implClass.getInterfaces()) {
            if (iface.isAnnotationPresent(WorkflowInterface.class)) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            implClass.getName() + " implements more than one @WorkflowInterface ("
                                    + found.getName() + ", " + iface.getName() + ")");
                }
                found = iface;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(implClass.getName() + " does not directly implement any @WorkflowInterface");
        }
        return found;
    }

    /** Every {@code @ActivityInterface} implClass implements (an implementation may satisfy more than one). */
    public static List<Class<?>> activityInterfacesOf(Class<?> implClass) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> iface : implClass.getInterfaces()) {
            if (iface.isAnnotationPresent(ActivityInterface.class)) {
                out.add(iface);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                    implClass.getName() + " does not directly implement any @ActivityInterface");
        }
        return out;
    }
}
