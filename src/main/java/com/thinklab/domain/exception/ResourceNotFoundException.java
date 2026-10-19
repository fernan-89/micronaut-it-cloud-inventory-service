package com.thinklab.domain.exception;

/** Domain Exception: no resource with that id in the organisation (another tenant's resource is the same as none). RFC 7807 mapping: HTTP 404 Not Found. */
public class ResourceNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-INV-00404";

    public ResourceNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
