package com.sluice.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CacheEntry(
        int status,
        Map<String, List<String>> headers,
        byte[] body,
        long storedAtMillis,
        long freshUntilMillis,
        long staleUntilMillis) {

    private static final int FORMAT_VERSION = 1;

    public boolean isFresh(long nowMillis) {
        return nowMillis < freshUntilMillis;
    }

    public boolean isServableStale(long nowMillis) {
        return nowMillis >= freshUntilMillis && nowMillis < staleUntilMillis;
    }

    public byte[] serialize() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length + 256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(status);
            out.writeLong(storedAtMillis);
            out.writeLong(freshUntilMillis);
            out.writeLong(staleUntilMillis);
            out.writeInt(headers.size());
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                out.writeUTF(header.getKey());
                out.writeInt(header.getValue().size());
                for (String value : header.getValue()) {
                    out.writeUTF(value);
                }
            }
            out.writeInt(body.length);
            out.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    public static CacheEntry deserialize(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported cache entry version " + version);
            }
            int status = in.readInt();
            long storedAt = in.readLong();
            long freshUntil = in.readLong();
            long staleUntil = in.readLong();
            int headerCount = in.readInt();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < headerCount; i++) {
                String name = in.readUTF();
                int valueCount = in.readInt();
                List<String> values = new ArrayList<>(valueCount);
                for (int j = 0; j < valueCount; j++) {
                    values.add(in.readUTF());
                }
                headers.put(name, values);
            }
            byte[] body = new byte[in.readInt()];
            in.readFully(body);
            return new CacheEntry(status, headers, body, storedAt, freshUntil, staleUntil);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
