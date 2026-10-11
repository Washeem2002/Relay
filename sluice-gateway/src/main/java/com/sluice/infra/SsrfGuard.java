package com.sluice.infra;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public final class SsrfGuard {

    private SsrfGuard() {
    }

    public static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    public static InetAddress[] resolveAllowed(String host, boolean allowPrivate) throws UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        if (allowPrivate) {
            return all;
        }
        List<InetAddress> permitted = new ArrayList<>();
        for (InetAddress address : all) {
            if (!isForbidden(address)) {
                permitted.add(address);
            }
        }
        if (permitted.isEmpty()) {
            throw new UnknownHostException("target " + host + " resolves to a private or reserved address");
        }
        return permitted.toArray(new InetAddress[0]);
    }
}
