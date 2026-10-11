package com.sluice.gateway;

import java.io.ByteArrayOutputStream;

public final class BodyCollector {

    private final long maxBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean overflowed;
    private boolean complete;

    public BodyCollector(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public void accept(byte[] data, int offset, int length) {
        if (overflowed) {
            return;
        }
        if (buffer.size() + (long) length > maxBytes) {
            overflowed = true;
            buffer.reset();
            return;
        }
        buffer.write(data, offset, length);
    }

    public void markComplete() {
        complete = true;
    }

    public boolean isUsable() {
        return complete && !overflowed;
    }

    public byte[] bytes() {
        return buffer.toByteArray();
    }
}
