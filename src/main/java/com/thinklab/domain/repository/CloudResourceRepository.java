package com.thinklab.domain.repository;

import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for CloudResource persistence. A resource is a snapshot the tool restates; every lookup is tenant-scoped. */
public interface CloudResourceRepository {

    /** Inserts the resource. The provider, account and the provider's own id are unique: a simultaneous second insert fails with a DuplicateResourceException. */
    Mono<CloudResource> create(CloudResource resource);

    /** Restates the stored resource: what the tool said replaces what was kept, and the moment it was seen only moves forward. */
    Mono<Void> save(CloudResource resource);

    Mono<CloudResource> findById(UUID id, UUID organisationId);

    Mono<CloudResource> findByKey(UUID organisationId, Provider provider, UUID accountId, String externalId);

    /** The tenant's resources, most recently seen first, at most {@code limit}; every filter that is not null narrows the list. */
    Flux<CloudResource> findAll(UUID organisationId, UUID accountId, Provider provider, Kind kind, String region, State state, int limit);
}
