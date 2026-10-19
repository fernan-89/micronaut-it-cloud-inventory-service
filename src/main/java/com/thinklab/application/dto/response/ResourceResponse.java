package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A resource as the tool last said it was, with how it is judged now. */
@Serdeable
public record ResourceResponse(UUID id, UUID organisationId, UUID accountId, String provider, String externalId, String name, String kind, String region, String state,
                               Map<String, String> tags, String presence, Instant firstSeenAt, Instant lastSeenAt, Instant updatedAt) {}
