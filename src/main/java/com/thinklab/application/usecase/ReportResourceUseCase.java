package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReportResourceRequest;
import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.mapper.InventoryMapper;
import com.thinklab.domain.exception.DuplicateResourceException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CloudResourceRepository;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Use Case for what the discovery tool reports (BIAN Behavior Qualifier: {@code initiate}), ADR-030. Idempotent: the provider's own id says which
 * resource it is, so a resource reported again is the same resource restated (200) and a new one is created (201). Two tools reporting the same
 * new resource at once end with one resource: the loser of the insert restates the stored one. A sovereign id is spent only for a new resource.
 * A terminated resource gets an end date (the retention), any other none.
 */
@Singleton
public class ReportResourceUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReportResourceUseCase.class);

    /** The resource as stored, and whether this call created it (201) or found it already there (200). */
    public record Reported(ResourceResponse resource, boolean created) {
    }

    private final HashServicePort hashServicePort;
    private final CloudResourceRepository repository;
    private final InventoryJudge judge;
    private final Clock clock;
    private final Duration terminatedRetention;

    public ReportResourceUseCase(HashServicePort hashServicePort, CloudResourceRepository repository, InventoryJudge judge, Clock clock,
                                 @Property(name = "thinklab.cloud-inventory.terminated-retention-days", defaultValue = "30") long terminatedRetentionDays) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.judge = judge;
        this.clock = clock;
        this.terminatedRetention = Duration.ofDays(terminatedRetentionDays);
    }

    public Mono<Reported> execute(UUID organisationId, ReportResourceRequest request, String executor, String role) {
        log.info("[USE CASE] Reporting a resource of CloudAccount ID: {}", request.accountId());
        return Mono.fromRunnable(() -> InventoryAccess.requireStaff(role, "report a resource"))
                .then(Mono.defer(() -> repository.findByKey(organisationId, request.provider(), request.accountId(), request.externalId())))
                .flatMap(existing -> restate(existing, request))
                .switchIfEmpty(Mono.defer(() -> store(organisationId, request, executor)))
                .map(stored -> new Reported(InventoryMapper.toResponse(stored.resource(), judge.presenceOf(stored.resource())), stored.created()));
    }

    private record Stored(CloudResource resource, boolean created) {
    }

    private Mono<Stored> restate(CloudResource resource, ReportResourceRequest request) {
        return Mono.fromRunnable(() -> {
                    resource.restate(request.name(), request.kind(), request.region(), request.state(), request.tags(), Instant.now(clock));
                    resource.expireAfter(terminatedRetention);
                })
                .then(Mono.defer(() -> repository.save(resource)))
                .thenReturn(new Stored(resource, false));
    }

    private Mono<Stored> store(UUID organisationId, ReportResourceRequest request, String executor) {
        Instant now = Instant.now(clock);
        return Mono.fromCallable(() -> newResource(UUID.randomUUID(), organisationId, request, executor, now))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("cloud-resource-creation")))
                .map(id -> newResource(id, organisationId, request, executor, now))
                .flatMap(repository::create)
                .map(created -> new Stored(created, true))
                .onErrorResume(DuplicateResourceException.class, error -> repository.findByKey(organisationId, request.provider(), request.accountId(), request.externalId())
                        .flatMap(existing -> restate(existing, request)));
    }

    private CloudResource newResource(UUID id, UUID organisationId, ReportResourceRequest request, String executor, Instant now) {
        CloudResource resource = CloudResource.createNew(id, organisationId, request.accountId(), request.provider(), request.externalId(), request.name(), request.kind(),
                request.region(), request.state(), request.tags(), executor, now);
        resource.expireAfter(terminatedRetention);
        return resource;
    }
}
