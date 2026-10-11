package com.sluice.gateway;

import java.util.ArrayList;
import java.util.List;

public final class RequestContext {

    private final String requestId;
    private final long startNanos = System.nanoTime();
    private final List<String> decisions = new ArrayList<>();
    private String route = "unmatched";
    private String tenant = "-";
    private String target = "-";
    private String pool = "-";
    private String cacheStatus;
    private int status;

    public RequestContext(String requestId) {
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    public String route() {
        return route;
    }

    public void route(String value) {
        this.route = value;
    }

    public String tenant() {
        return tenant;
    }

    public void tenant(String value) {
        this.tenant = value;
    }

    public String target() {
        return target;
    }

    public void target(String value) {
        this.target = value;
    }

    public String pool() {
        return pool;
    }

    public void pool(String value) {
        this.pool = value;
    }

    public String cacheStatus() {
        return cacheStatus;
    }

    public void cacheStatus(String value) {
        this.cacheStatus = value;
    }

    public int status() {
        return status;
    }

    public void status(int value) {
        this.status = value;
    }

    public void decide(String decision) {
        decisions.add(decision);
    }

    public String decisions() {
        return decisions.isEmpty() ? "proxied" : String.join(",", decisions);
    }
}
