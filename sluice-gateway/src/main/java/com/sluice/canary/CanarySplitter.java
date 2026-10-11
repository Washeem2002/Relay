package com.sluice.canary;

import com.sluice.balancing.Hashing;
import com.sluice.routing.StickyKey;
import com.sluice.schema.CanaryConfig;
import java.util.Locale;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class CanarySplitter {

    public static final String OVERRIDE_HEADER = "X-Canary";
    public static final int BUCKETS = 100;

    public CanaryDecision decide(String routeName, CanaryConfig canary, Function<String, String> header,
                                 Function<String, String> cookie, String clientIp) {
        if (canary == null) {
            return CanaryDecision.stable("no-canary");
        }
        String override = header.apply(OVERRIDE_HEADER);
        if (override != null) {
            String value = override.trim().toLowerCase(Locale.ROOT);
            if (value.equals("always")) {
                return CanaryDecision.canary("header-always");
            }
            if (value.equals("never")) {
                return CanaryDecision.stable("header-never");
            }
        }
        if (canary.weight() <= 0) {
            return CanaryDecision.stable("weight-zero");
        }
        int bucket = bucketFor(routeName, StickyKey.resolve(canary.stickyKey(), header, cookie, clientIp));
        return bucket < canary.weight() ? CanaryDecision.canary("bucket") : CanaryDecision.stable("bucket");
    }

    public int bucketFor(String routeName, String stickyValue) {
        return Hashing.bucket(routeName + ":" + stickyValue, BUCKETS);
    }
}
