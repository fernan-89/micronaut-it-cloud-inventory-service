package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use the cloud inventory (staff only, ADR-032). RFC 7807 mapping: HTTP 403 Forbidden. */
public class InventoryAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-INV-00403";

    public InventoryAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
