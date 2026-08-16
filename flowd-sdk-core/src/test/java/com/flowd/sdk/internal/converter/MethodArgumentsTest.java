package com.flowd.sdk.internal.converter;

import com.flowd.api.v1.Payload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the multi-argument {@code @WorkflowMethod}/{@code @ActivityMethod}
 * support described in ARCHITECTURE.md §10: arity 0/1 must round-trip
 * exactly as it always has (a bare value or nothing — see the equivalence
 * asserted against {@link JsonDataConverter#toPayload}/{@code fromPayload(Payload, Class)}
 * directly), and arity &gt; 1 packs/unpacks as a positional JSON array.
 */
class MethodArgumentsTest {
    private final DataConverter converter = JsonDataConverter.INSTANCE;

    @Test
    void packZeroArgsIsNull() {
        assertNull(MethodArguments.pack(new Object[0]));
        assertNull(MethodArguments.pack(null));
    }

    @Test
    void packOneArgIsTheValueItself() {
        assertEquals("hello", MethodArguments.pack(new Object[]{"hello"}));
    }

    @Test
    void packMultipleArgsIsTheArrayItself() {
        Object[] args = {"orderId", 42};
        assertEquals(args, MethodArguments.pack(args));
    }

    @Test
    void unpackZeroParamsIsEmptyArray() throws DataConverterException {
        assertArrayEquals(new Object[0], MethodArguments.unpack(converter, null, new Class<?>[0]));
    }

    @Test
    void unpackOneParamMatchesLegacySingleValueEncoding() throws DataConverterException {
        Payload payload = converter.toPayload("orderId-123");
        Object[] out = MethodArguments.unpack(converter, payload, new Class<?>[]{String.class});
        assertArrayEquals(new Object[]{"orderId-123"}, out);
    }

    @Test
    void roundTripsMultipleHeterogeneousArgumentsByPosition() throws DataConverterException {
        Object[] original = {"orderId-123", 42, true};
        Payload payload = converter.toPayload(MethodArguments.pack(original));

        Object[] decoded = MethodArguments.unpack(converter, payload,
                new Class<?>[]{String.class, Integer.class, Boolean.class});

        assertArrayEquals(original, decoded);
    }

    @Test
    void roundTripsAPojoArgumentAmongMultiple() throws DataConverterException {
        record Address(String city, int zip) {
        }
        Object[] original = {"orderId-123", new Address("Springfield", 90210)};
        Payload payload = converter.toPayload(MethodArguments.pack(original));

        Object[] decoded = MethodArguments.unpack(converter, payload, new Class<?>[]{String.class, Address.class});

        assertEquals("orderId-123", decoded[0]);
        assertEquals(new Address("Springfield", 90210), decoded[1]);
    }

    @Test
    void arityMismatchFailsLoudly() throws DataConverterException {
        Payload payload = converter.toPayload(new Object[]{"one", "two"});
        assertThrows(DataConverterException.class, () ->
                MethodArguments.unpack(converter, payload, new Class<?>[]{String.class, String.class, String.class}));
    }

    @Test
    void nonArrayPayloadFailsLoudlyForMultiArgUnpack() throws DataConverterException {
        Payload payload = converter.toPayload("just a string");
        assertThrows(DataConverterException.class, () ->
                MethodArguments.unpack(converter, payload, new Class<?>[]{String.class, String.class}));
    }
}
