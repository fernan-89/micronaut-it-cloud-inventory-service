# ADR-035: The Promises Contract

## Status
Accepted. Implements platform ADR-001 (`thinklab-platform/docs/adr/001-the-promises-contract.md`).

## Context
The alerting service used to know this registry's fields and verdicts (which states are a broken promise, and the code of each). A rule that lived in a consumer was untested with the domain and had to be kept in step by hand.

## Decision
- The registry answers `GET /it-cloud-inventory/v1/promises/retrieve?changedWithinDays=&limit=` as the platform contract says: every broken promise and every one that changed within the window (30 days unless asked, 1 to 365), at most `limit` of each (500 unless asked, 1 to 1000), broken first.
- **The judgement lives here:** a resource is **broken** (`CLOUD_RESOURCE_MISSING`) when the discovery tool has not seen it for more than `thinklab.cloud-inventory.missing-after-hours` (48), and **kept** when seen lately and also when the tool reported it terminated (removed on purpose: an alert about its absence is settled). A terminated resource has the status `TERMINATED`, any other `ACTIVE`, so only a live one can open an alert. The name is the resource's name; it has no asset here.
- **`changedAt`:** the last moment the resource's description changed, **or it was seen again after being missing** (which is what settles the promise; a routine sighting that changes nothing does not move it, so the thousands of resources a tool re-sees every day are not all 'changed').
- Staff only, tenant-scoped, read-only.

## Consequences
- Positive: the rule is judged and tested with the domain; the consumer needs no knowledge of this registry.
- Negative: both sets are asked of the store: the missing ones (the least recently seen, never cut off a list of the most recent ones, ADR-032) and the ones changed within the window.
