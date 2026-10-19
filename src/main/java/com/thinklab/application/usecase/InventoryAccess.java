package com.thinklab.application.usecase;

import com.thinklab.domain.exception.InventoryAccessDeniedException;

/** The cloud inventory is for IT staff and for the tools they run (ADR-034): a REQUESTER is refused everywhere. */
final class InventoryAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private InventoryAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new InventoryAccessDeniedException("A requester cannot " + operation + ": the cloud inventory is handled by IT staff.");
        }
    }
}
