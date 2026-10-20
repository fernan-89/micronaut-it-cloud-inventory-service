package com.thinklab.domain.repository;

import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Outbound Port for CloudResource persistence. A resource is a snapshot the tool restates; every lookup is tenant-scoped. */
public interface CloudResourceRepository {

    /** Inserts the resource. The provider, account and the provider's own id are unique: a simultaneous second insert fails with a DuplicateResourceException. */
    Mono<CloudResource> create(CloudResource resource);

    /** Restates the stored resource: what the tool said replaces what was kept, and the moment it was seen only moves forward. */
    Mono<Void> save(CloudResource resource);

    Mono<CloudResource> findById(UUID id, UUID organisationId);

    Mono<CloudResource> findByKey(UUID organisationId, Provider provider, UUID accountId, String externalId);

    /**
     * The tenant's resources, most recently seen first, at most {@code limit}; every filter that is not null narrows the list. The presence is judged
     * in the store, so the limit applies to what was asked for: {@code MISSING} is not terminated and last seen before {@code missingBefore}, {@code PRESENT}
     * is not terminated and last seen then or later, {@code GONE} is terminated. A resource that is missing is the least recently seen, so it must never be
     * filtered after a limit has cut the list to the most recent ones.
     */
    Flux<CloudResource> findAll(UUID organisationId, UUID accountId, Provider provider, Kind kind, String region, State state, Presence presence, Instant missingBefore, int limit);

    /** The tenant's resources last changed at or after that moment (the description changed, or it reappeared after being missing), most recently changed first, at most {@code limit}. */
    Flux<CloudResource> findChangedSince(UUID organisationId, Instant since, int limit);

    default Flux<CloudResource> findAll(UUID organisationId, UUID accountId, Provider provider, Kind kind, String region, State state, int limit) {
        return findAll(organisationId, accountId, provider, kind, region, state, null, null, limit);
    }
}
