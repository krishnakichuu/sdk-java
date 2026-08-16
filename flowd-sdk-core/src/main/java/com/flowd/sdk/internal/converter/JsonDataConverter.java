package com.flowd.sdk.internal.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowd.api.v1.Payload;
import com.google.protobuf.ByteString;

/**
 * The default JSON DataConverter, matching Go's wire format exactly:
 * metadata["encoding"] = "json/plain", data = plain JSON bytes. A Go worker
 * and a Java worker/client can freely interoperate on the same task queue
 * as long as both sides use this default (see JsonDataConverterTest for a
 * round-trip check against a Go-shaped payload).
 */
public final class JsonDataConverter implements DataConverter {

    /** The default DataConverter used unless a client/worker is configured with another implementation. */
    public static final DataConverter INSTANCE = new JsonDataConverter();

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonDataConverter() {
    }

    @Override
    public Payload toPayload(Object value) throws DataConverterException {
        try {
            byte[] data = mapper.writeValueAsBytes(value);
            return Payload.newBuilder()
                    .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
                    .setData(ByteString.copyFrom(data))
                    .build();
        } catch (Exception e) {
            throw new DataConverterException("converter: marshal: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T fromPayload(Payload payload, Class<T> type) throws DataConverterException {
        if (payload == null || payload.getData().isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(payload.getData().toByteArray(), type);
        } catch (Exception e) {
            throw new DataConverterException("converter: unmarshal: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T fromPayload(Payload payload, TypeReference<T> type) throws DataConverterException {
        if (payload == null || payload.getData().isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(payload.getData().toByteArray(), type);
        } catch (Exception e) {
            throw new DataConverterException("converter: unmarshal: " + e.getMessage(), e);
        }
    }

    @Override
    public Object[] fromPayload(Payload payload, Class<?>[] types) throws DataConverterException {
        if (payload == null || payload.getData().isEmpty()) {
            return new Object[types.length];
        }
        try {
            JsonNode array = mapper.readTree(payload.getData().toByteArray());
            if (!array.isArray()) {
                throw new DataConverterException(
                        "converter: expected a JSON array of " + types.length + " positional arguments, got " + array.getNodeType());
            }
            if (array.size() != types.length) {
                throw new DataConverterException(
                        "converter: expected " + types.length + " positional arguments, got " + array.size());
            }
            Object[] out = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                out[i] = mapper.treeToValue(array.get(i), types[i]);
            }
            return out;
        } catch (DataConverterException e) {
            throw e;
        } catch (Exception e) {
            throw new DataConverterException("converter: unmarshal: " + e.getMessage(), e);
        }
    }
}
