package com.sluice.resilience;

import java.time.Clock;

public final class RollingCounter {

    public record Counts(long total, long flagged) {

        public int flaggedPercent() {
            return total == 0 ? 0 : (int) (flagged * 100 / total);
        }
    }

    private final Clock clock;
    private final long bucketMillis;
    private final int bucketCount;
    private final long[] bucketIds;
    private final long[] totals;
    private final long[] flagged;

    public RollingCounter(Clock clock, long windowMillis, int bucketCount) {
        this.clock = clock;
        this.bucketCount = bucketCount;
        this.bucketMillis = Math.max(1, windowMillis / bucketCount);
        this.bucketIds = new long[bucketCount];
        this.totals = new long[bucketCount];
        this.flagged = new long[bucketCount];
        java.util.Arrays.fill(bucketIds, -1);
    }

    public synchronized void record(boolean isFlagged) {
        long id = currentBucketId();
        int index = (int) (id % bucketCount);
        if (bucketIds[index] != id) {
            bucketIds[index] = id;
            totals[index] = 0;
            flagged[index] = 0;
        }
        totals[index]++;
        if (isFlagged) {
            flagged[index]++;
        }
    }

    public synchronized Counts counts() {
        long id = currentBucketId();
        long total = 0;
        long flaggedTotal = 0;
        for (int i = 0; i < bucketCount; i++) {
            if (bucketIds[i] >= 0 && bucketIds[i] > id - bucketCount) {
                total += totals[i];
                flaggedTotal += flagged[i];
            }
        }
        return new Counts(total, flaggedTotal);
    }

    public synchronized void reset() {
        java.util.Arrays.fill(bucketIds, -1);
        java.util.Arrays.fill(totals, 0);
        java.util.Arrays.fill(flagged, 0);
    }

    private long currentBucketId() {
        return clock.millis() / bucketMillis;
    }
}
