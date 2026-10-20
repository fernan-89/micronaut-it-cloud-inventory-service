package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.ReportResourceRequest;
import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.dto.response.ResourceSummaryResponse;
import com.thinklab.application.usecase.ReportResourceUseCase;
import com.thinklab.application.usecase.ReportResourceUseCase.Reported;
import com.thinklab.application.usecase.RetrieveResourceSummaryUseCase;
import com.thinklab.application.usecase.RetrievePromisesUseCase;
import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.application.usecase.RetrieveResourceUseCase;
import com.thinklab.application.usecase.RetrieveResourcesUseCase;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class CloudInventoryControllerTest {

    private static final String EXECUTOR = "discovery";
    private static final String ROLE = "ENGINEER";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @Mock private ReportResourceUseCase reportResource;
    @Mock private RetrieveResourceUseCase retrieveResource;
    @Mock private RetrieveResourcesUseCase retrieveResources;
    @Mock private RetrieveResourceSummaryUseCase retrieveSummary;
    @Mock private RetrievePromisesUseCase retrievePromises;

    private CloudInventoryController controller() {
        return new CloudInventoryController(reportResource, retrieveResource, retrieveResources, retrieveSummary, retrievePromises);
    }

    private ResourceResponse response() {
        return new ResourceResponse(id, tenant, UUID.randomUUID(), "AWS", "i-0abc", "web-1", "COMPUTE", "us-east-1", "RUNNING", Map.of(), "PRESENT", now, now, now);
    }

    @Test
    @DisplayName("a new resource is 201 and one already in the inventory is 200")
    void report() {
        ReportResourceRequest request = new ReportResourceRequest(UUID.randomUUID(), Provider.AWS, "i-0abc", "web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, null);
        when(reportResource.execute(tenant, request, EXECUTOR, ROLE)).thenReturn(Mono.just(new Reported(response(), true))).thenReturn(Mono.just(new Reported(response(), false)));

        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().initiate(tenantHeader, EXECUTOR, ROLE, request)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("one resource, the list with its filters, and the summary pass the tenant and the role")
    void reads() {
        UUID accountId = UUID.randomUUID();
        when(retrieveResource.execute(id, tenant, ROLE)).thenReturn(Mono.just(response()));
        when(retrieveResources.execute(tenant, accountId, Provider.AWS, Kind.COMPUTE, "us-east-1", State.RUNNING, Presence.PRESENT, 10, ROLE)).thenReturn(Flux.just(response()));
        when(retrieveSummary.execute(tenant, Provider.AZURE, ROLE)).thenReturn(Mono.just(new ResourceSummaryResponse(0, Map.of(), Map.of(), Map.of(), Map.of())));

        StepVerifier.create(controller().retrieve(id, tenantHeader, ROLE)).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller().retrieveAll(tenantHeader, ROLE, accountId, Provider.AWS, Kind.COMPUTE, "us-east-1", State.RUNNING, Presence.PRESENT, 10))
                .assertNext(l -> assertEquals(1, l.size())).verifyComplete();
        StepVerifier.create(controller().summary(tenantHeader, ROLE, Provider.AZURE)).assertNext(s -> assertEquals(0, s.total())).verifyComplete();
    }

    @Test
    @DisplayName("the promises route passes the tenant, the role and the two parameters (platform ADR-001)")
    void promises() {
        when(retrievePromises.execute(tenant, 7, 50, ROLE)).thenReturn(Mono.just(List.of(new PromiseResponse(UUID.randomUUID(), "web-1", null, "KEPT", null, "ACTIVE", now))));

        StepVerifier.create(controller().promises(tenantHeader, ROLE, 7, 50)).assertNext(promises -> assertEquals(1, promises.size())).verifyComplete();
    }
}
