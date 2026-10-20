package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateResourceException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.domain.repository.CloudResourceRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud inventory against a real MongoDB: one resource per (organisation, provider, account, the provider's id) even under twenty simultaneous
 * inserts, the moment seen that only moves forward, the filters of the list, the tenant scope, and the unique, list and expiry (TTL) indexes.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloudInventoryPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "cloud_inventory_it";
    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject CloudResourceRepository resources;
    @Inject MongoClient mongoClient;

    private CloudResource newResource(UUID organisation, UUID account, Provider provider, String externalId, Kind kind, String region, State state, Instant seen) {
        return CloudResource.createNew(UUID.randomUUID(), organisation, account, provider, externalId, "res-1", kind, region, state, Map.of("environment", "prod"), "discovery", seen);
    }

    @Test
    @DisplayName("a resource is stored and found by id and by key, and another tenant sees nothing")
    void storeAndFind() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        CloudResource resource = newResource(org, account, Provider.AWS, "arn:aws:ec2:us-east-1:1:instance/i-1", Kind.COMPUTE, "us-east-1", State.RUNNING, T0);

        resources.create(resource).block();

        CloudResource found = resources.findById(resource.getId(), org).block();
        assertNotNull(found);
        assertEquals(Map.of("environment", "prod"), found.getTags());
        assertEquals(resource.getId(), resources.findByKey(org, Provider.AWS, account, resource.getExternalId()).block().getId());
        assertNull(resources.findById(resource.getId(), UUID.randomUUID()).block());
        assertNull(resources.findByKey(UUID.randomUUID(), Provider.AWS, account, resource.getExternalId()).block());
        assertNull(resources.findByKey(org, Provider.AZURE, account, resource.getExternalId()).block());
    }

    @Test
    @DisplayName("the same resource inserted twenty times at once is one resource: nineteen get a DuplicateResourceException")
    void oneResourceUnderRace() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        String externalId = "/subscriptions/1/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm1";

        List<Boolean> created = Flux.range(0, 20).flatMap(i -> resources.create(newResource(org, account, Provider.AZURE, externalId, Kind.COMPUTE, "brazilsouth", State.RUNNING, T0))
                .map(r -> true).onErrorResume(DuplicateResourceException.class, e -> Mono.just(false)).subscribeOn(Schedulers.parallel())).collectList().block();

        assertEquals(1, created.stream().filter(c -> c).count());
        assertEquals(19, created.stream().filter(c -> !c).count());
        assertEquals(1, resources.findAll(org, account, Provider.AZURE, null, null, null, 50).count().block());
    }

    @Test
    @DisplayName("restating replaces what was kept, but the moment seen only moves forward whatever the order of the reports")
    void restateOnlyForward() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        CloudResource resource = newResource(org, account, Provider.AWS, "i-2", Kind.COMPUTE, "us-east-1", State.RUNNING, T0);
        resources.create(resource).block();

        CloudResource later = resources.findById(resource.getId(), org).block();
        later.restate("res-2", Kind.DATABASE, "eu-west-1", State.TERMINATED, null, T0.plusSeconds(3600));
        later.expireAfter(Duration.ofDays(30));
        resources.save(later).block();
        CloudResource earlier = resources.findById(resource.getId(), org).block();
        earlier.restate("res-3", Kind.DATABASE, "eu-west-1", State.TERMINATED, null, T0.plusSeconds(60));
        resources.save(earlier).block();

        CloudResource stored = resources.findById(resource.getId(), org).block();
        assertEquals("res-3", stored.getName());
        assertEquals(State.TERMINATED, stored.getState());
        assertEquals(T0.plusSeconds(3600), stored.getLastSeenAt());
        assertTrue(stored.getTags().isEmpty());
        assertNotNull(stored.getPurgeAt());
    }

    @Test
    @DisplayName("the list is scoped to the tenant, narrowed by each filter, most recently seen first and limited")
    void list() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        resources.create(newResource(org, account, Provider.AWS, "i-a", Kind.COMPUTE, "us-east-1", State.RUNNING, T0)).block();
        resources.create(newResource(org, account, Provider.AWS, "i-b", Kind.DATABASE, "us-east-1", State.STOPPED, T0.plusSeconds(60))).block();
        resources.create(newResource(org, UUID.randomUUID(), Provider.AZURE, "vm-c", Kind.COMPUTE, "brazilsouth", State.RUNNING, T0.plusSeconds(120))).block();
        resources.create(newResource(UUID.randomUUID(), account, Provider.AWS, "i-other", Kind.COMPUTE, "us-east-1", State.RUNNING, T0)).block();

        List<CloudResource> all = resources.findAll(org, null, null, null, null, null, 50).collectList().block();
        assertEquals(List.of("vm-c", "i-b", "i-a"), all.stream().map(CloudResource::getExternalId).toList());
        assertEquals(2, resources.findAll(org, account, null, null, null, null, 50).count().block());
        assertEquals(1, resources.findAll(org, null, Provider.AZURE, null, null, null, 50).count().block());
        assertEquals(1, resources.findAll(org, null, null, Kind.DATABASE, null, null, 50).count().block());
        assertEquals(2, resources.findAll(org, null, null, null, "us-east-1", null, 50).count().block());
        assertEquals(1, resources.findAll(org, null, null, null, null, State.STOPPED, 50).count().block());
        assertEquals(2, resources.findAll(org, null, null, null, null, null, 2).count().block());
    }

    @Test
    @DisplayName("a missing resource is found even behind more recently seen ones than the limit: the presence is judged in the store, not after the limit")
    void presenceBeyondTheLimit() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-10T12:00:00Z");
        Instant cutoff = now.minus(Duration.ofHours(48));
        resources.create(newResource(org, account, Provider.AWS, "i-missing", Kind.COMPUTE, "us-east-1", State.RUNNING, now.minus(Duration.ofHours(100)))).block();
        for (int i = 0; i < 5; i++) {
            resources.create(newResource(org, account, Provider.AWS, "i-fresh-" + i, Kind.COMPUTE, "us-east-1", State.RUNNING, now.minusSeconds(60 + i))).block();
        }
        resources.create(newResource(org, account, Provider.AWS, "i-gone", Kind.COMPUTE, "us-east-1", State.TERMINATED, now.minus(Duration.ofHours(200)))).block();

        assertEquals(List.of("i-missing"), resources.findAll(org, null, null, null, null, null, Presence.MISSING, cutoff, 2).map(CloudResource::getExternalId).collectList().block());
        assertEquals(2, resources.findAll(org, null, null, null, null, null, Presence.PRESENT, cutoff, 2).count().block());
        assertEquals(5, resources.findAll(org, null, null, null, null, null, Presence.PRESENT, cutoff, 50).count().block());
        assertEquals(List.of("i-gone"), resources.findAll(org, null, null, null, null, null, Presence.GONE, cutoff, 2).map(CloudResource::getExternalId).collectList().block());
    }

    @Test
    @DisplayName("the resources changed since a moment are the tenant's, most recently changed first, and the limit applies")
    void changedSince() {
        UUID org = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        resources.create(newResource(org, account, Provider.AWS, "i-old", Kind.COMPUTE, "us-east-1", State.RUNNING, T0.minus(Duration.ofDays(60)))).block();
        resources.create(newResource(org, account, Provider.AWS, "i-new", Kind.COMPUTE, "us-east-1", State.RUNNING, T0)).block();
        resources.create(newResource(org, account, Provider.AWS, "i-newer", Kind.COMPUTE, "us-east-1", State.RUNNING, T0.plusSeconds(60))).block();
        resources.create(newResource(UUID.randomUUID(), account, Provider.AWS, "i-other", Kind.COMPUTE, "us-east-1", State.RUNNING, T0.plusSeconds(120))).block();

        assertEquals(List.of("i-newer", "i-new"), resources.findChangedSince(org, T0.minus(Duration.ofDays(1)), 10).map(CloudResource::getExternalId).collectList().block());
        assertEquals(List.of("i-newer"), resources.findChangedSince(org, T0.minus(Duration.ofDays(1)), 1).map(CloudResource::getExternalId).collectList().block());
        assertEquals(3, resources.findChangedSince(org, T0.minus(Duration.ofDays(90)), 10).count().block());
    }

    @Test
    @DisplayName("the unique key, the list index and the expiry (TTL) index that counts from purgeAt exist")
    void indexes() {
        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("cloud_resources").listIndexes()).collectList().block();

        Document key = indexes.stream().filter(i -> "organisationId_1_provider_1_accountId_1_externalId_1".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(true, key.getBoolean("unique"));
        assertTrue(indexes.stream().anyMatch(i -> "organisationId_1_lastSeenAt_-1".equals(i.getString("name"))));
        Document expiry = indexes.stream().filter(i -> "purgeAt_1".equals(i.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) expiry.get("expireAfterSeconds")).longValue());
        assertThrows(DuplicateResourceException.class, () -> {
            UUID org = UUID.randomUUID();
            UUID account = UUID.randomUUID();
            resources.create(newResource(org, account, Provider.AWS, "i-dup", Kind.COMPUTE, "us-east-1", State.RUNNING, T0)).block();
            resources.create(newResource(org, account, Provider.AWS, "i-dup", Kind.COMPUTE, "us-east-1", State.RUNNING, T0)).block();
        });
    }
}
