package com.thinklab.domain.exception;

/** Domain Exception: the same resource (provider, account and the provider's own id) was inserted twice at once. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateResourceException extends BusinessException {

    private static final String ERROR_CODE = "ERR-INV-00409";

    public DuplicateResourceException(String message) {
        super(ERROR_CODE, message);
    }
}
