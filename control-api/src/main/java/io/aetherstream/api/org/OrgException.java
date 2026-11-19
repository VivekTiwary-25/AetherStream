package io.aetherstream.api.org;

import org.springframework.http.HttpStatus;

public final class OrgException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OrgException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
