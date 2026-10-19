package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReportResourceRequest;
import com.thinklab.application.dto.response.ResourceResponse;
import com.thinklab.application.dto.response.ResourceSummaryResponse;
import com.thinklab.application.mapper.InventoryMapper;
import com.thinklab.domain.exception.DuplicateResourceException;
import com.thinklab.domain.exception.InventoryAccessDeniedException;
import com.thinklab.domain.exception.ResourceNotFoundException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CloudResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudInventoryUseCasesTest {

    private static final String STAFF = "ENGINEER";
    private static final String REQUESTER = "REQUESTER";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String ARN = "arn:aws:ec2:us-east-1:123456789012:instance/i-0abc";

    private final UUID org = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private HashServicePort hash;
    private CloudResourceRepository repository;
    private InventoryJudge judge;
    private ReportResourceUseCase report;

    @BeforeEach
    void setUp() {
        hash = mock(HashServicePort.class);
        repository = mock(CloudResourceRepository.class);
        when(hash.generateSovereignId(any())).thenAnswer(call -> Mono.just(UUID.randomUUID()));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(repository.save(any())).thenReturn(Mono.empty());
        judge = new InventoryJudge(clock, 48);
        report = new ReportResourceUseCase(hash, repository, judge, clock, 30);
    }

    private ReportResourceRequest request(State state) {
        return new ReportResourceRequest(account, Provider.AWS, ARN, "web-1", Kind.COMPUTE, "us-east-1", state, Map.of("environment", "prod"));
    }

    private CloudResource stored(Instant seen, State state) {
        return CloudResource.reconstitute(UUID.randomUUID(), org, account, Provider.AWS, ARN, "web-1", Kind.COMPUTE, "us-east-1", state, Map.of("environment", "prod"),
                seen, seen, seen, null);
    }

    @Test
    @DisplayName("a new resource is created (201) with a sovereign id, judged PRESENT, with no end date")
    void reportsNew() {
        when(repository.findByKey(org, Provider.AWS, account, ARN)).thenReturn(Mono.empty());

        StepVerifier.create(report.execute(org, request(State.RUNNING), "discovery", STAFF)).assertNext(reported -> {
            assertTrue(reported.created());
            assertEquals("PRESENT", reported.resource().presence());
            assertEquals("web-1", reported.resource().name());
            assertEquals(NOW, reported.resource().lastSeenAt());
        }).verifyComplete();
        verify(hash).generateSovereignId("cloud-resource-creation");
    }

    @Test
    @DisplayName("a new resource that is already terminated is created with an end date")
    void reportsNewTerminated() {
        when(repository.findByKey(org, Provider.AWS, account, ARN)).thenReturn(Mono.empty());

        StepVerifier.create(report.execute(org, request(State.TERMINATED), "discovery", STAFF)).assertNext(reported -> assertEquals("GONE", reported.resource().presence())).verifyComplete();
        verify(repository).create(any(CloudResource.class));
    }

    @Test
    @DisplayName("a resource already in the inventory is restated (200), not created, and no sovereign id is spent")
    void reportsExisting() {
        CloudResource existing = stored(NOW.minusSeconds(3600), State.RUNNING);
        when(repository.findByKey(org, Provider.AWS, account, ARN)).thenReturn(Mono.just(existing));

        StepVerifier.create(report.execute(org, request(State.TERMINATED), "discovery", STAFF)).assertNext(reported -> {
            assertFalse(reported.created());
            assertEquals("GONE", reported.resource().presence());
            assertEquals(NOW, reported.resource().lastSeenAt());
        }).verifyComplete();
        verify(repository).save(existing);
        verify(hash, never()).generateSovereignId(any());
        assertTrue(existing.getPurgeAt() != null);
    }

    @Test
    @DisplayName("two tools reporting the same new resource at once end with one: the loser of the insert restates the stored one")
    void reportsRace() {
        CloudResource winner = stored(NOW.minusSeconds(5), State.RUNNING);
        when(repository.findByKey(org, Provider.AWS, account, ARN)).thenReturn(Mono.empty()).thenReturn(Mono.just(winner));
        doReturn(Mono.error(new DuplicateResourceException("dup"))).when(repository).create(any());

        StepVerifier.create(report.execute(org, request(State.RUNNING), "discovery", STAFF)).assertNext(reported -> assertFalse(reported.created())).verifyComplete();
        verify(repository).save(winner);
    }

    @Test
    @DisplayName("a REQUESTER is refused everywhere, and a bad value is refused before an id is spent")
    void refusals() {
        assertThrows(InventoryAccessDeniedException.class, () -> report.execute(org, request(State.RUNNING), "discovery", REQUESTER).block());
        assertThrows(InventoryAccessDeniedException.class, () -> new RetrieveResourceUseCase(repository, judge).execute(UUID.randomUUID(), org, REQUESTER).block());
        assertThrows(InventoryAccessDeniedException.class, () -> new RetrieveResourcesUseCase(repository, judge).execute(org, null, null, null, null, null, null, null, REQUESTER).blockLast());
        assertThrows(InventoryAccessDeniedException.class, () -> new RetrieveResourceSummaryUseCase(repository, judge).execute(org, null, REQUESTER).block());
        when(repository.findByKey(any(), any(), any(), any())).thenReturn(Mono.empty());
        ReportResourceRequest bad = new ReportResourceRequest(account, Provider.AWS, ARN, "web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, Map.of("secret", "x"));
        assertThrows(IllegalArgumentException.class, () -> report.execute(org, bad, "discovery", STAFF).block());
        verify(hash, never()).generateSovereignId(any());
    }

    @Test
    @DisplayName("one resource is judged now: MISSING after the allowed silence; another tenant's is a 404")
    void retrieveOne() {
        CloudResource old = stored(NOW.minusSeconds(49 * 3600L), State.RUNNING);
        when(repository.findById(any(UUID.class), any(UUID.class))).thenReturn(Mono.empty());
        when(repository.findById(old.getId(), org)).thenReturn(Mono.just(old));
        RetrieveResourceUseCase retrieve = new RetrieveResourceUseCase(repository, judge);

        StepVerifier.create(retrieve.execute(old.getId(), org, STAFF)).assertNext(found -> assertEquals("MISSING", found.presence())).verifyComplete();
        StepVerifier.create(retrieve.execute(UUID.randomUUID(), org, STAFF)).expectError(ResourceNotFoundException.class).verify();
    }

    @Test
    @DisplayName("the list is limited (100 unless asked, at most 500, at least 1) and filtered by how the resources are judged")
    void list() {
        CloudResource fresh = stored(NOW.minusSeconds(60), State.RUNNING);
        CloudResource missing = stored(NOW.minusSeconds(100 * 3600L), State.RUNNING);
        CloudResource gone = stored(NOW.minusSeconds(60), State.TERMINATED);
        when(repository.findAll(eq(org), any(), any(), any(), any(), any(), anyInt())).thenReturn(Flux.just(fresh, missing, gone));
        RetrieveResourcesUseCase list = new RetrieveResourcesUseCase(repository, judge);

        StepVerifier.create(list.execute(org, null, null, null, null, null, null, null, STAFF)).expectNextCount(3).verifyComplete();
        verify(repository).findAll(org, null, null, null, null, null, 100);
        StepVerifier.create(list.execute(org, account, Provider.AWS, Kind.COMPUTE, "us-east-1", State.RUNNING, null, 9999, STAFF)).expectNextCount(3).verifyComplete();
        verify(repository).findAll(org, account, Provider.AWS, Kind.COMPUTE, "us-east-1", State.RUNNING, 500);
        StepVerifier.create(list.execute(org, null, null, null, null, null, null, 0, STAFF)).expectNextCount(1).verifyComplete();
        StepVerifier.create(list.execute(org, null, null, null, null, null, Presence.MISSING, 10, STAFF)).assertNext(found -> assertEquals("MISSING", found.presence())).verifyComplete();
        verify(repository).findAll(org, null, null, null, null, null, 500);
        StepVerifier.create(list.execute(org, null, null, null, null, null, Presence.GONE, 10, STAFF)).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("the summary counts by provider, kind, state and presence, and nothing else of the resources")
    void summary() {
        CloudResource fresh = stored(NOW.minusSeconds(60), State.RUNNING);
        CloudResource gone = stored(NOW.minusSeconds(60), State.TERMINATED);
        when(repository.findAll(org, null, Provider.AWS, null, null, null, 5000)).thenReturn(Flux.just(fresh, fresh, gone));
        when(repository.findAll(org, null, null, null, null, null, 5000)).thenReturn(Flux.empty());
        RetrieveResourceSummaryUseCase summary = new RetrieveResourceSummaryUseCase(repository, judge);

        StepVerifier.create(summary.execute(org, Provider.AWS, STAFF)).assertNext((ResourceSummaryResponse counted) -> {
            assertEquals(3, counted.total());
            assertEquals(Map.of("AWS", 3), counted.byProvider());
            assertEquals(Map.of("COMPUTE", 3), counted.byKind());
            assertEquals(Map.of("RUNNING", 2, "TERMINATED", 1), counted.byState());
            assertEquals(Map.of("PRESENT", 2, "GONE", 1), counted.byPresence());
        }).verifyComplete();
        StepVerifier.create(summary.execute(org, null, STAFF)).assertNext(counted -> {
            assertEquals(0, counted.total());
            assertTrue(counted.byKind().isEmpty());
        }).verifyComplete();
    }

    @Test
    @DisplayName("the mapper answers every field, and it and the access check are utility classes")
    void mapperAndUtilities() throws ReflectiveOperationException {
        CloudResource resource = stored(NOW, State.RUNNING);
        ResourceResponse response = InventoryMapper.toResponse(resource, Presence.PRESENT);

        assertEquals(resource.getId(), response.id());
        assertEquals("AWS", response.provider());
        assertEquals(ARN, response.externalId());
        assertEquals(Map.of("environment", "prod"), response.tags());
        for (Class<?> utility : new Class<?>[]{InventoryMapper.class, InventoryAccess.class}) {
            Constructor<?> constructor = utility.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThrows(InvocationTargetException.class, constructor::newInstance);
        }
    }
}
