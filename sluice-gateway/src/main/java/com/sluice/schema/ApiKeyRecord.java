package com.sluice.schema;

import java.time.Instant;

public record ApiKeyRecord(String id, String hash, String prefix, Instant createdAt, boolean revoked) {
}
