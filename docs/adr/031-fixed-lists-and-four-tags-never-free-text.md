# ADR-031: Fixed Lists and Four Tags, Never Free Text

## Status
Accepted. Same principle as the DR and licence registries.

## Context
A resource report is easy to fill with what must not travel: a tag holding a person's name or an e-mail, a connection string in a name, a password in a "description". The registry is read by many screens and tools.

## Decision
- **Fixed lists**: provider (`AWS`, `AZURE`), kind (`COMPUTE`, `DATABASE`, `STORAGE`, `NETWORK`, `CONTAINER`, `SERVERLESS`, `OTHER`) and state (`RUNNING`, `STOPPED`, `TERMINATED`). A value outside a list is a 400 that does not repeat the value.
- **Identifiers, not text**: the name (up to 128), the region (2 to 40 lowercase letters, digits or dashes: `us-east-1`, `brazilsouth`) and each tag value (up to 64) accept only letters, digits, `.`, `_`, `:`, `-` and (name) `/`: no spaces, no `@`, no quotes. The region is a pattern and not a list because both clouds add regions often.
- **Four tag keys only**: `environment`, `owner`, `cost-center`, `application`. Any other key is a 400. A tag the organisation uses for something else has nowhere to go, by design.

## Consequences
- Positive: nothing in a report can carry a secret or a personal name in prose form; the data can be shown anywhere.
- Negative: a tool must translate its tags to these four keys, and a person's name as `owner` must be a team or an identifier.
