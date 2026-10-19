package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ResourceSummaryResponse;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.repository.CloudResourceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Use Case for how many resources the tenant has (BIAN Behavior Qualifier: {@code summary/retrieve}): counts by provider, kind, state and how they
 * are judged now, optionally of one provider. Counted over at most {@link #COUNTED} resources, most recently seen first.
 */
@Singleton
public class RetrieveResourceSummaryUseCase {

    static final int COUNTED = 5000;
    private static final Logger log = LoggerFactory.getLogger(RetrieveResourceSummaryUseCase.class);

    private final CloudResourceRepository repository;
    private final InventoryJudge judge;

    public RetrieveResourceSummaryUseCase(CloudResourceRepository repository, InventoryJudge judge) {
        this.repository = repository;
        this.judge = judge;
    }

    public Mono<ResourceSummaryResponse> execute(UUID organisationId, Provider provider, String role) {
        log.info("[USE CASE] Counting the CloudResources of organisation: {}", organisationId);
        return Mono.fromRunnable(() -> InventoryAccess.requireStaff(role, "count resources"))
                .then(Mono.defer(() -> repository.findAll(organisationId, null, provider, null, null, null, COUNTED).collectList()))
                .map(resources -> new ResourceSummaryResponse(resources.size(),
                        count(resources, resource -> resource.getProvider().name()),
                        count(resources, resource -> resource.getKind().name()),
                        count(resources, resource -> resource.getState().name()),
                        count(resources, resource -> judge.presenceOf(resource).name())));
    }

    private static Map<String, Integer> count(List<CloudResource> resources, Function<CloudResource, String> key) {
        Map<String, Integer> counts = new TreeMap<>();
        resources.forEach(resource -> counts.merge(key.apply(resource), 1, Integer::sum));
        return counts;
    }
}
