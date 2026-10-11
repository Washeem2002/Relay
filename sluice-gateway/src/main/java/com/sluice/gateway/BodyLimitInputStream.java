package com.sluice.gateway;

import java.io.IOException;
import java.io.InputStream;

public final class BodyLimitInputStream extends InputStream {

    private final InputStream delegate;
    private final long limit;
    private long bytesRead;

    public BodyLimitInputStream(InputStream delegate, long limit) {
        this.delegate = delegate;
        this.limit = limit;
    }

    public long bytesRead() {
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        int value;
        try {
            value = delegate.read();
        } catch (IOException e) {
            throw new ClientReadException(e);
        }
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read;
        try {
            read = delegate.read(buffer, offset, length);
        } catch (IOException e) {
            throw new ClientReadException(e);
        }
        if (read > 0) {
            count(read);
        }
        return read;
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private void count(int amount) throws PayloadTooLargeException {
        bytesRead += amount;
        if (bytesRead > limit) {
            throw new PayloadTooLargeException(limit);
        }
    }
}
