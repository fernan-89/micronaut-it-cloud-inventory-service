package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.ReportResourceRequest;
import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.dto.response.ResourceSummaryResponse;
import com.thinklab.application.usecase.RetrievePromisesUseCase;
import com.thinklab.application.usecase.ReportResourceUseCase;
import com.thinklab.application.usecase.RetrieveResourceSummaryUseCase;
import com.thinklab.application.usecase.RetrieveResourceUseCase;
import com.thinklab.application.usecase.RetrieveResourcesUseCase;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-cloud-inventory} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.CloudResource} is the Control Record, at the root. The tool
 * reports a resource with {@code initiate} (the same resource again is the same resource, restated); there is no {@code update} (the tool says
 * what the resource is) and no {@code DELETE} (a terminated resource is kept for a while, then expires).
 *
 * <p><b>Tenant on every route, staff only (ADR-034):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's resource is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-INV-00403}.
 */
@Controller("/it-cloud-inventory/v1")
public class CloudInventoryController {

    private static final Logger log = LoggerFactory.getLogger(CloudInventoryController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final ReportResourceUseCase reportResource;
    private final RetrieveResourceUseCase retrieveResource;
    private final RetrieveResourcesUseCase retrieveResources;
    private final RetrieveResourceSummaryUseCase retrieveSummary;

    private final RetrievePromisesUseCase retrievePromises;

    public CloudInventoryController(ReportResourceUseCase reportResource, RetrieveResourceUseCase retrieveResource, RetrieveResourcesUseCase retrieveResources,
                                    RetrieveResourceSummaryUseCase retrieveSummary,
            RetrievePromisesUseCase retrievePromises) {
        this.retrievePromises = retrievePromises;
        this.reportResource = reportResource;
        this.retrieveResource = retrieveResource;
        this.retrieveResources = retrieveResources;
        this.retrieveSummary = retrieveSummary;
    }

    /** Behavior Qualifier: {@code initiate}. What the tool says a resource is: 201 for a new resource, 200 for one already in the inventory (restated). */
    @Post("/initiate")
    public Mono<HttpResponse<ResourceResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid ReportResourceRequest request
    ) {
        log.info("[ACTION: REPORT_RESOURCE] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return reportResource.execute(UUID.fromString(tenantId), request, executor, role)
                .map(reported -> reported.created() ? HttpResponse.created(reported.resource()) : HttpResponse.ok(reported.resource()));
    }

    /** Behavior Qualifier: {@code retrieve}. One resource of the tenant, judged now. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<ResourceResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveResource.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), most recently seen first, filterable, at most 500 (100 unless asked). */
    @Get("/retrieve")
    public Mono<List<ResourceResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                    @QueryValue @Nullable UUID accountId, @QueryValue @Nullable Provider provider, @QueryValue @Nullable Kind kind,
                                                    @QueryValue @Nullable String region, @QueryValue @Nullable State state, @QueryValue @Nullable Presence presence,
                                                    @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrieveResources.execute(UUID.fromString(tenantId), accountId, provider, kind, region, state, presence, limit, role).collectList());
    }

    /**
     * Behavior Qualifier: {@code promises/retrieve} (platform ADR-001, the promises contract). Every broken promise and every one that changed within the days asked
     * (30 unless asked, 1 to 365), at most {@code limit} of each (500 unless asked, at most 1000).
     */
    @Get("/promises/retrieve")
    public Mono<List<PromiseResponse>> promises(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                @QueryValue @Nullable Integer changedWithinDays, @QueryValue @Nullable Integer limit) {
        return Mono.defer(() -> retrievePromises.execute(UUID.fromString(tenantId), changedWithinDays, limit, role));
    }

    /** Behavior Qualifier: {@code summary/retrieve}. How many resources, by provider, kind, state and presence. */
    @Get("/summary/retrieve")
    public Mono<ResourceSummaryResponse> summary(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role, @QueryValue @Nullable Provider provider) {
        return Mono.defer(() -> retrieveSummary.execute(UUID.fromString(tenantId), provider, role));
    }
}
