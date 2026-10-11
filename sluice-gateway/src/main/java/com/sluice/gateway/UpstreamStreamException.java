package com.sluice.gateway;

import java.io.IOException;

public class UpstreamStreamException extends IOException {

    public UpstreamStreamException(IOException cause) {
        super("upstream failed while streaming the response body", cause);
    }
}
