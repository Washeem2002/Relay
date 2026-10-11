package com.sluice.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public final class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    public MutableClock() {
        this(1_700_000_000_000L);
    }

    public void advance(Duration duration) {
        millis.addAndGet(duration.toMillis());
    }

    public void advanceMillis(long value) {
        millis.addAndGet(value);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }
}
