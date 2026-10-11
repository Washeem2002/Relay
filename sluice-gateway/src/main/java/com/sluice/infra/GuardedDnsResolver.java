package com.sluice.infra;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

public final class GuardedDnsResolver implements DnsResolver {

    private final boolean allowPrivate;

    public GuardedDnsResolver(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return SsrfGuard.resolveAllowed(host, allowPrivate);
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return InetAddress.getByName(host).getCanonicalHostName();
    }
}
