package com.sluice.cache;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class RequestCoalescer {

    public record Ticket(boolean leader, CompletableFuture<Optional<CacheEntry>> future) {
    }

    private final ConcurrentMap<String, CompletableFuture<Optional<CacheEntry>>> inFlight = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public Ticket join(String key) {
        CompletableFuture<Optional<CacheEntry>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<CacheEntry>> existing = inFlight.putIfAbsent(key, mine);
        if (existing == null) {
            return new Ticket(true, mine);
        }
        return new Ticket(false, existing);
    }

    public void complete(String key, Ticket ticket, Optional<CacheEntry> result) {
        inFlight.remove(key, ticket.future());
        ticket.future().complete(result);
    }

    public Optional<CacheEntry> await(Ticket ticket, long timeoutMillis) {
        try {
            return ticket.future().get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            return Optional.empty();
        }
    }

    public boolean tryStartRefresh(String key) {
        return refreshing.add(key);
    }

    public void finishRefresh(String key) {
        refreshing.remove(key);
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
