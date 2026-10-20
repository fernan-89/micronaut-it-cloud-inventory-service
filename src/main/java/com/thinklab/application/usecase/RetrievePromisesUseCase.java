package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.Promise;
import com.thinklab.domain.repository.CloudResourceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use Case for the promises of the tenant's resources, judged now (BIAN Behavior Qualifier: {@code promises/retrieve}, platform ADR-001): a resource the tool has
 * not seen for too long is broken ({@code CLOUD_RESOURCE_MISSING}); one seen lately, or terminated on purpose, is kept (a terminated one has the status
 * {@code TERMINATED}, so it never opens an alert). Both sets are asked of the store itself, because a missing resource is the least recently seen and a resource
 * that came back is not among the newest created: the missing ones, and the ones that changed within the window. Staff only.
 */
@Singleton
public class RetrievePromisesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrievePromisesUseCase.class);

    private final CloudResourceRepository repository;
    private final InventoryJudge judge;
    private final Clock clock;

    public RetrievePromisesUseCase(CloudResourceRepository repository, InventoryJudge judge, Clock clock) {
        this.repository = repository;
        this.judge = judge;
        this.clock = clock;
    }

    public Mono<List<PromiseResponse>> execute(UUID organisationId, Integer changedWithinDays, Integer limit, String role) {
        log.info("[USE CASE] Retrieving the promises of the CloudResources of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> InventoryAccess.requireStaff(role, "list the promises of resources"))
                .then(Mono.fromCallable(() -> new int[]{PromiseSelection.days(changedWithinDays), PromiseSelection.limit(limit)}))
                .flatMap(window -> {
                    Instant since = Instant.now(clock).minus(Duration.ofDays(window[0]));
                    return Mono.zip(repository.findAll(organisationId, null, null, null, null, null, Presence.MISSING, judge.missingBefore(), window[1]).collectList(),
                                    repository.findChangedSince(organisationId, since, window[1]).collectList())
                            .map(read -> {
                                Map<UUID, PromiseResponse> all = new LinkedHashMap<>();
                                read.getT1().forEach(resource -> all.putIfAbsent(resource.getId(), toResponse(resource)));
                                read.getT2().forEach(resource -> all.putIfAbsent(resource.getId(), toResponse(resource)));
                                return PromiseSelection.select(List.copyOf(all.values()), since, window[1]);
                            });
                });
    }

    private PromiseResponse toResponse(CloudResource resource) {
        Promise promise = CloudResource.promise(judge.presenceOf(resource));
        return new PromiseResponse(resource.getId(), resource.getName(), null, promise.verdict().name(), promise.code(), resource.promiseStatus(), resource.getUpdatedAt());
    }
}
