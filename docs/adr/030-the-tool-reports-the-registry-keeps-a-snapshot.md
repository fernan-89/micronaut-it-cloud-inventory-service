# ADR-030: The Tool Reports, the Registry Keeps a Snapshot

## Status
Accepted. Same shape as the cloud cost registry (its ADR-030).

## Context
Journey 15 needs to know which resources the cloud accounts run. What exists is what the provider's APIs (or a discovery tool such as Steampipe or a cloud asset export) know. The user chose **AWS and Azure first**, a resource that **points to the account of the cloud cost registry**, an **external id unique per account**, and a **snapshot of the current state**, not a history.

## Decision
- **The registry only records.** A `CloudResource` is one resource the tool said exists: the account it belongs to (`accountId`, the id of a cloud cost registry account, **not checked**: the registries stay independent), the provider (`AWS` or `AZURE`), the provider's own id for it (`externalId`: an ARN, or an Azure resource id, up to 300 letters, digits, `.`, `_`, `:`, `-` or `/`), a name, a kind, a region, a state and up to four tags. The registry never calls a cloud and never holds a credential.
- **The provider's id is the identity.** The same provider, account and `externalId` in an organisation is the same resource (a unique index). Reporting it again answers 200 with the stored resource restated; a new one is 201. Two tools reporting the same new resource at once end with one resource.
- **A snapshot, not a history.** What the tool says replaces what was kept; the moment the resource was last seen only ever moves forward, whatever the order of the reports. Nothing of the old values is kept.
- **No update and no delete by hand.** The tool says what the resource is; a resource that no longer exists is reported `TERMINATED` and leaves the store by itself (ADR-033).
- **Another provider is a later decision**, with its own value of the list, not free text.

## Consequences
- Positive: no cloud credentials, no cloud SDK, one small contract that any discovery script can feed.
- Negative: the registry trusts the tool; it cannot tell a resource the tool never listed from one that does not exist. It keeps no history of changes.
