package com.sluice.balancing;

import java.nio.charset.StandardCharsets;

public final class Hashing {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Hashing() {
    }

    public static long hash64(String value) {
        long hash = FNV_OFFSET;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return mix(hash);
    }

    public static int bucket(String value, int buckets) {
        return (int) Long.remainderUnsigned(hash64(value), buckets);
    }

    private static long mix(long value) {
        long h = value;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
