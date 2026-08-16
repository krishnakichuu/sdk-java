package com.flowd.sdk.spring;

import com.flowd.sdk.workflow.WorkflowInterface;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

import java.io.IOException;
import java.lang.reflect.Modifier;

/**
 * Matches a concrete class that directly implements some {@code
 * @WorkflowInterface}-annotated interface — used to classpath-scan for
 * workflow implementation classes (see {@link FlowdWorkerAutoConfiguration}),
 * since those are deliberately not {@code @Component} beans themselves
 * (Spring never constructs them; {@code FlowdWorker} does, fresh per
 * execution) and so cannot be found through Spring's normal component scan.
 */
final class WorkflowImplementationTypeFilter implements TypeFilter {
    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) throws IOException {
        String className = metadataReader.getClassMetadata().getClassName();
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, getClass().getClassLoader());
        } catch (Throwable e) {
            return false; // unresolvable on this classloader — not a candidate, not an error
        }
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
            return false;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface.isAnnotationPresent(WorkflowInterface.class)) {
                return true;
            }
        }
        return false;
    }
}
