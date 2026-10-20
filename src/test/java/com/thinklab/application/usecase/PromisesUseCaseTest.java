package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.PromiseResponse;
import com.thinklab.domain.exception.InventoryAccessDeniedException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.domain.repository.CloudResourceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromisesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private final UUID org = UUID.randomUUID();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CloudResourceRepository repository = mock(CloudResourceRepository.class);
    private final RetrievePromisesUseCase useCase = new RetrievePromisesUseCase(repository, new InventoryJudge(clock, 48), clock);

    private CloudResource resource(String name, State state, Instant lastSeen, Instant updatedAt) {
        return CloudResource.reconstitute(UUID.randomUUID(), org, UUID.randomUUID(), Provider.AWS, "i-" + name, name, Kind.COMPUTE, "us-east-1", state, Map.of(), NOW.minus(Duration.ofDays(90)),
                lastSeen, updatedAt, null);
    }

    private void reads(List<CloudResource> missing, List<CloudResource> changed) {
        when(repository.findAll(eq(org), any(), any(), any(), any(), any(), eq(Presence.MISSING), any(), anyInt())).thenReturn(Flux.fromIterable(missing));
        when(repository.findChangedSince(eq(org), any(), anyInt())).thenReturn(Flux.fromIterable(changed));
    }

    @Test
    @DisplayName("the missing resources are asked of the store (the least recently seen), with the changed ones; a resource in both is listed once, broken first; a terminated one is kept with the status TERMINATED")
    void selects() {
        CloudResource gone = resource("gone", State.RUNNING, NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)));
        CloudResource back = resource("back", State.RUNNING, NOW.minus(Duration.ofMinutes(5)), NOW.minus(Duration.ofHours(1)));
        CloudResource terminated = resource("old", State.TERMINATED, NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofHours(3)));
        reads(List.of(gone), List.of(back, gone, terminated));

        StepVerifier.create(useCase.execute(org, null, null, "ENGINEER")).assertNext(promises -> {
            assertEquals(List.of("gone", "back", "old"), promises.stream().map(PromiseResponse::name).toList());
            assertEquals("BROKEN", promises.get(0).verdict());
            assertEquals("CLOUD_RESOURCE_MISSING", promises.get(0).code());
            assertEquals("ACTIVE", promises.get(0).status());
            assertNull(promises.get(0).assetId());
            assertEquals("KEPT", promises.get(1).verdict());
            assertEquals(NOW.minus(Duration.ofHours(1)), promises.get(1).changedAt());
            assertEquals("TERMINATED", promises.get(2).status());
            assertEquals("KEPT", promises.get(2).verdict());
        }).verifyComplete();
        verify(repository).findAll(eq(org), any(), any(), any(), any(), any(), eq(Presence.MISSING), eq(NOW.minus(Duration.ofHours(48))), eq(500));
        verify(repository).findChangedSince(org, NOW.minus(Duration.ofDays(30)), 500);
    }

    @Test
    @DisplayName("the window and the limit are the ones asked and are given to the store")
    void window() {
        reads(List.of(), List.of());

        StepVerifier.create(useCase.execute(org, 7, 50, "ENGINEER")).expectNext(List.of()).verifyComplete();

        verify(repository).findChangedSince(org, NOW.minus(Duration.ofDays(7)), 50);
    }

    @Test
    @DisplayName("the days must be 1 to 365 and the limit 1 to 1000; a requester is refused")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 0, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, 366, null, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 0, "ENGINEER").block());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(org, null, 1001, "ENGINEER").block());
        assertThrows(InventoryAccessDeniedException.class, () -> useCase.execute(org, null, null, "REQUESTER").block());
    }

    @Test
    @DisplayName("the selection is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<PromiseSelection> constructor = PromiseSelection.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
