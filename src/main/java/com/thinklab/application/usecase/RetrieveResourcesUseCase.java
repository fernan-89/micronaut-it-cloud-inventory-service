package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.mapper.InventoryMapper;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.domain.repository.CloudResourceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for listing the tenant's resources, most recently seen first, filterable by account, provider, kind, region, state and how they are
 * judged (BIAN Behavior Qualifier: {@code retrieve}, collection). Presence is judged when read (ADR-032) and filtered in the store, so the limit applies to what was asked for; at most
 * {@link #MAX_LIMIT} resources (100 unless asked).
 */
@Singleton
public class RetrieveResourcesUseCase {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    private static final Logger log = LoggerFactory.getLogger(RetrieveResourcesUseCase.class);

    private final CloudResourceRepository repository;
    private final InventoryJudge judge;

    public RetrieveResourcesUseCase(CloudResourceRepository repository, InventoryJudge judge) {
        this.repository = repository;
        this.judge = judge;
    }

    public Flux<ResourceResponse> execute(UUID organisationId, UUID accountId, Provider provider, Kind kind, String region, State state, Presence presence, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the CloudResources of organisation: {}", organisationId);
        int cap = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return Mono.fromRunnable(() -> InventoryAccess.requireStaff(role, "list resources"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId, accountId, provider, kind, region, state, presence, judge.missingBefore(), cap)))
                .map(resource -> InventoryMapper.toResponse(resource, judge.presenceOf(resource)));
    }
}
