# ADR-033: Guarded Writes, a Unique Backstop and Expiring Terminated Resources

## Status
Accepted. Same discipline as the other registries (their ADR-033).

## Context
Several tools may report at once, in any order, and the same resource more than once. A terminated resource has no use after a while, but a resource that is simply not listed must not be deleted by guesswork.

## Decision
- **The unique index is the backstop** of ADR-030: one resource per `(organisation, provider, account, externalId)`. The loser of a simultaneous insert gets a duplicate error inside the registry, which finds the stored resource and restates it (200).
- **The moment seen only moves forward** (a database `max`), so the order of two reports does not matter.
- **Only a terminated resource expires.** When a resource is terminated, `purgeAt` is the moment it was last seen plus `thinklab.cloud-inventory.terminated-retention-days` (default 30); a resource in any other state has no `purgeAt`. A TTL index on `purgeAt` removes it. A resource that comes back (a new report with another state) loses its `purgeAt`.
- **A resource that is merely missing is never deleted.** The registry says it is `MISSING` and keeps it.

## Consequences
- Positive: no cleanup job, no wrong deletion, repeatable under load.
- Negative: the TTL monitor of the database runs about once a minute, so removal is not exact to the second.
