package com.flowd.sdk.worker;

import com.flowd.api.v1.Payload;
import com.flowd.sdk.internal.converter.DataConverter;
import com.flowd.sdk.internal.converter.DataConverterException;

/**
 * Decodes a task's raw input {@link Payload} into whatever a registered
 * {@link WorkflowHandler}/{@link ActivityHandler} expects as {@code I}. The
 * common case — {@code registerWorkflow(name, Class<I>, handler)} /
 * {@code registerActivity(...)} — builds one of these from a single
 * {@code Class<I>} via {@code converter.fromPayload(payload, inputType)};
 * the annotation-based multi-parameter path
 * ({@code FlowdWorker.registerWorkflowImplementationType}/
 * {@code registerActivityMethod}, for a method with more than one formal
 * parameter) supplies its own that decodes a positional JSON array into an
 * {@code Object[]} instead — see
 * {@code com.flowd.sdk.internal.converter.MethodArguments#unpack}.
 */
@FunctionalInterface
public interface PayloadDecoder<I> {
    I decode(DataConverter converter, Payload payload) throws DataConverterException;
}
