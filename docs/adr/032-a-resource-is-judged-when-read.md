# ADR-032: A Resource Is Judged When Read

## Status
Accepted. Same idea as the cloud cost registry (its ADR-031).

## Context
Whether a resource is still there depends on the clock, not on a report: a tool that stops listing it says nothing. Storing the verdict would need a scheduler that changes it, which the registry does not have.

## Decision
- The registry stores no verdict. When a resource is read it is judged **`presence`**: `GONE` when the tool said it is `TERMINATED`; `MISSING` when it is not terminated and the tool has not seen it for more than `thinklab.cloud-inventory.missing-after-hours` (default 48); `PRESENT` otherwise.
- The list can be filtered by `presence`, and the filter is asked of the store (missing: not terminated and last seen before the allowed silence; present: not terminated and seen then or later; gone: terminated), so the limit applies to what was asked for. A missing resource is the least recently seen one, so it must never be looked for among the most recent ones only. The summary counts at most 5,000 resources, most recently seen first.
- Nothing of the judgment is stored, so a changed setting changes the answer for every resource at once.

## Consequences
- Positive: no scheduler, no stale verdict, no write on read.
- Negative: the store and the code each state what MISSING means (a moment compared with the last seen), and the two must stay the same. A later pairing with alerting can turn `MISSING` into an alert.
