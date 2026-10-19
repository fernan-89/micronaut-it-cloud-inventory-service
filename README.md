# micronaut-it-cloud-inventory-service

BIAN-aligned Service Domain **it-cloud-inventory** (Control Record: `CloudResource`), port `8113`.

A slice of Journey 15 (breadth): a **registry of cloud resources**, for **AWS and Azure**. A `CloudResource` is one thing a cloud account runs (a
virtual machine, a database, a bucket...) as the discovery tool last said it was. It points to an account of the cloud cost registry. The registry
says, **when you ask**, whether a resource is still present, has gone missing from the tool's sight, or is gone.

## What it guarantees, and what it does not

- **It only records** (ADR-030): the tool reports each resource (`initiate`). The registry never calls a cloud, reads an API or holds a credential. The
  account is the id of a cloud cost registry account and is **not checked**.
- **The provider's id is the identity** (ADR-030): the same provider, account and `externalId` (an ARN, an Azure resource id) is the same resource,
  restated (200); a new one is 201, even with 20 tools reporting it at once. A **snapshot**: what the tool says replaces what was kept.
- **Fixed lists and four tags** (ADR-031): provider `AWS`/`AZURE`, kind (`COMPUTE`, `DATABASE`, `STORAGE`, `NETWORK`, `CONTAINER`, `SERVERLESS`,
  `OTHER`), state (`RUNNING`, `STOPPED`, `TERMINATED`); name, region and tag values are identifiers, never free text; only the tag keys
  `environment`, `owner`, `cost-center` and `application` are kept.
- **A resource is judged when read** (ADR-032), never stored: `presence` is `GONE` (the tool said terminated), `MISSING` (not seen for more than
  `thinklab.cloud-inventory.missing-after-hours`, 48) or `PRESENT`.
- **Safe with several instances** (ADR-033): one resource per `(organisation, provider, account, externalId)`, the moment seen only moves forward, and a
  **terminated** resource leaves the store `thinklab.cloud-inventory.terminated-retention-days` (30) after it was last seen. A missing one is never deleted.
- **Staff only** (ADR-034): every route refuses a `REQUESTER` with 403 `ERR-INV-00403`; another tenant's resource answers 404.
- **Not here yet:** other providers (GCP, OCI), history of changes, a link to the asset registry (promoting a resource to an asset), telling someone when
  resources go missing (a later pairing with alerting), cost per resource.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on `initiate` (the name of the tool); `X-Role` is optional and, with platform
security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-cloud-inventory/v1/initiate` `{"accountId":"<uuid>","provider":"AWS","externalId":"arn:aws:ec2:us-east-1:123456789012:instance/i-0abc","name":"web-1","kind":"COMPUTE","region":"us-east-1","state":"RUNNING","tags":{"environment":"prod","owner":"team-a"}}` (201 new, 200 restated) |
| retrieve | `GET /it-cloud-inventory/v1/{id}/retrieve` (judged now) |
| retrieve (collection) | `GET /it-cloud-inventory/v1/retrieve?accountId=&provider=&kind=&region=&state=&presence=&limit=` (most recently seen first, 100 unless asked, at most 500) |
| summary/retrieve | `GET /it-cloud-inventory/v1/summary/retrieve?provider=` (counts by provider, kind, state and presence, over at most 5,000 resources) |

```
resource: the provider's id is its identity, a snapshot, restated on a repeat, judged PRESENT / MISSING / GONE when read
          terminated -> leaves the store after the retention (TTL on purgeAt)
```

```bash
curl -X POST "http://localhost:8113/it-cloud-inventory/v1/initiate" -H "X-Tenant-Id: <organisationId>" -H "X-Executor: steampipe-sync" \
  -H "Content-Type: application/json" \
  -d '{"accountId":"<cloud cost account id>","provider":"AZURE","externalId":"/subscriptions/0000/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/vm1","name":"vm1","kind":"COMPUTE","region":"brazilsouth","state":"RUNNING"}'
```

## Errors (RFC 7807, `error_code`)

| Code | HTTP | When |
|---|---|---|
| `ERR-INV-00403` | 403 | A REQUESTER used the cloud inventory (ADR-034) |
| `ERR-INV-00404` | 404 | No such resource in the organisation |
| `ERR-INV-00409` | 409 | The same resource was inserted twice at once (handled inside the registry; not seen by callers) |
| `ERR-VALIDATION-00400` | 400 | A value outside a list, an identifier that does not match, a tag key that is not kept |

## Run, test, configure

```bash
./gradlew run                 # port 8113, MongoDB at MONGODB_URI (default mongodb://localhost:27017/thinklab_it_cloud_inventory_db)
./gradlew check               # unit tests + 100% line and branch gate + integration tests (Testcontainers, needs Docker)
```

`thinklab.mongo.create-indexes` (default on), `THINKLAB_CLOUD_INVENTORY_MISSING_AFTER_HOURS` (default 48),
`THINKLAB_CLOUD_INVENTORY_TERMINATED_RETENTION_DAYS` (default 30). The service calls no other service and needs only the sovereign id service of the
platform (`HASH_SERVICE_URL`) for the id of a new resource.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 the tool reports, the registry keeps a snapshot · 031 fixed lists and four tags, never free text · 032 a resource is judged when read ·
033 guarded writes, a unique backstop and expiring terminated resources · 034 staff only.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
