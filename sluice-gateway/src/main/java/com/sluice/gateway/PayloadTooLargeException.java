package com.sluice.gateway;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long limit) {
        super("request body exceeds " + limit + " bytes");
    }
}
