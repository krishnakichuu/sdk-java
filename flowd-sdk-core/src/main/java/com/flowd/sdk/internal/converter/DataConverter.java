package com.flowd.sdk.internal.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flowd.api.v1.Payload;

/**
 * DataConverter marshals Java values to/from the Payload envelope used on
 * the wire and in history. Mirrors sdk/internal/converter/converter.go
 * (Go): made pluggable from day one — not bolted on later — because
 * production workflow histories are serialized with whatever converter was
 * active at the time; changing the wire format after the fact is far more
 * expensive than designing for it now.
 */
public interface DataConverter {
    Payload toPayload(Object value) throws DataConverterException;

    <T> T fromPayload(Payload payload, Class<T> type) throws DataConverterException;

    <T> T fromPayload(Payload payload, TypeReference<T> type) throws DataConverterException;

    /**
     * Decodes a payload holding a positional array of values — one per
     * {@code types[i]} — used by multi-parameter
     * {@code @WorkflowMethod}/{@code @ActivityMethod} calls (see
     * {@code MethodArguments}). {@code types.length} is always &gt; 1;
     * arity 0/1 calls never reach this method. The default throws, so an
     * existing DataConverter implementation with no notion of a positional
     * array (a non-JSON encoding, say) keeps compiling unchanged and simply
     * doesn't support multi-argument methods until it opts in.
     */
    default Object[] fromPayload(Payload payload, Class<?>[] types) throws DataConverterException {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support multi-argument (positional array) payloads");
    }
}
