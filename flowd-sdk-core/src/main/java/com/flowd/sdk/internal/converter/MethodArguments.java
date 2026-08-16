package com.flowd.sdk.internal.converter;

import com.flowd.api.v1.Payload;

/**
 * Packs/unpacks the positional argument list of an annotation-based
 * {@code @WorkflowMethod}/{@code @ActivityMethod} call into flowd's
 * one-{@link Payload}-per-call wire contract. Zero or one argument is
 * encoded exactly as it always has been (a bare value, or nothing) — only
 * two or more arguments take the new shape, a positional JSON array decoded
 * back by matching formal parameter type — so this is purely additive: it
 * changes no existing wire format (see ARCHITECTURE.md §10's
 * "multi-argument methods" note).
 */
public final class MethodArguments {
    private MethodArguments() {
    }

    /**
     * Packs call arguments into whatever a single {@link DataConverter#toPayload}
     * call should encode: 0 args -&gt; {@code null}, 1 arg -&gt; that value
     * itself (unchanged from before multi-arg support existed), &gt;1 args
     * -&gt; the array itself, which {@link DataConverter#toPayload} encodes
     * as a positional JSON array (see {@link #unpack} for the inverse).
     */
    public static Object pack(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        if (args.length == 1) {
            return args[0];
        }
        return args;
    }

    /**
     * Inverse of {@link #pack}: decodes payload into {@code paramTypes.length}
     * values, one per formal parameter, by position. Arity 0/1 decode via
     * the same {@link DataConverter#fromPayload(Payload, Class)} call used
     * before multi-arg support existed; only arity &gt; 1 reaches
     * {@link DataConverter#fromPayload(Payload, Class[])}.
     */
    public static Object[] unpack(DataConverter converter, Payload payload, Class<?>[] paramTypes) throws DataConverterException {
        if (paramTypes.length == 0) {
            return new Object[0];
        }
        if (paramTypes.length == 1) {
            return new Object[]{converter.fromPayload(payload, paramTypes[0])};
        }
        return converter.fromPayload(payload, paramTypes);
    }
}
