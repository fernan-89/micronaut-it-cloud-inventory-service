# ADR-034: Staff Only

## Status
Accepted. Same as the other registries (their ADR-032).

## Context
The inventory shows how the organisation's cloud is laid out. That is for IT staff and the tools they run, not for a requester of the service desk.

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-INV-00403`; the role comes from `X-Role`, and with platform security on, from the verified token.
- Every route needs `X-Tenant-Id`; another tenant's resource is a 404 `ERR-INV-00404`, the same as a resource that does not exist.
- A value outside a fixed list, or an identifier that does not match, never appears in the answer or in the log.

## Consequences
- Positive: no cross-tenant reading, no hint of what other tenants have.
- Negative: a requester who needs to know where something runs asks staff.
