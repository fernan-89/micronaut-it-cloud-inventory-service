package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.mapper.InventoryMapper;
import com.thinklab.domain.exception.ResourceNotFoundException;
import com.thinklab.domain.repository.CloudResourceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for one resource of the tenant, judged now (BIAN Behavior Qualifier: {@code retrieve}). Another tenant's resource is a 404. */
@Singleton
public class RetrieveResourceUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveResourceUseCase.class);

    private final CloudResourceRepository repository;
    private final InventoryJudge judge;

    public RetrieveResourceUseCase(CloudResourceRepository repository, InventoryJudge judge) {
        this.repository = repository;
        this.judge = judge;
    }

    public Mono<ResourceResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving CloudResource ID: {}", id);
        return Mono.fromRunnable(() -> InventoryAccess.requireStaff(role, "read a resource"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("CloudResource " + id + " not found.")))
                .map(resource -> InventoryMapper.toResponse(resource, judge.presenceOf(resource)));
    }
}
