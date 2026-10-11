package com.sluice.gateway;

import java.io.IOException;

public class ClientReadException extends IOException {

    public ClientReadException(IOException cause) {
        super("client request body could not be read", cause);
    }
}
