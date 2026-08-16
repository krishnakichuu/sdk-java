package com.flowd.sdk.internal.sync;

import com.flowd.sdk.internal.converter.MethodArguments;
import com.flowd.sdk.internal.registry.AnnotationSupport;
import com.flowd.sdk.workflow.ActivityInvocation;
import com.flowd.sdk.workflow.ActivityOptions;
import com.flowd.sdk.workflow.WorkflowContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Backs the proxy returned by {@code Workflow.newActivityStub}: each
 * interface method call becomes one {@code WorkflowContext.executeActivity}
 * call against whichever WorkflowContext is bound to the calling coroutine
 * right now (see {@link WorkflowThreadContext}), synchronously blocking
 * (via the coroutine's own yield/resume cycle — see {@code
 * ActivityFuture#get}) until the result is known, then returning it
 * directly — the synchronous-looking call Temporal-style workflow code
 * expects, backed underneath by the exact same deterministic
 * schedule/replay primitive the lower-level API uses explicitly.
 */
public final class ActivityStubInvocationHandler implements InvocationHandler {
    private final Class<?> activityInterface;
    private final ActivityOptions options;

    public ActivityStubInvocationHandler(Class<?> activityInterface, ActivityOptions options) {
        this.activityInterface = activityInterface;
        this.options = options;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "ActivityStub[" + activityInterface.getName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                default -> null;
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        WorkflowContext ctx = WorkflowThreadContext.current();
        String activityType = AnnotationSupport.activityTypeName(method);
        Object arg = MethodArguments.pack(args);
        ActivityInvocation invocation = ctx.executeActivity(activityType, arg, options);

        Class<?> returnType = method.getReturnType();
        if (returnType == void.class) {
            invocation.get(null);
            return null;
        }
        return invocation.get(returnType);
    }
}
