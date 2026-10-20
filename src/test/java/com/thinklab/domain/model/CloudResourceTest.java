package com.thinklab.domain.model;

import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudResourceTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private static final String ARN = "arn:aws:ec2:us-east-1:123456789012:instance/i-0abc";
    private static final String AZURE = "/subscriptions/0000-1111/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/vm1";

    private CloudResource resource() {
        return CloudResource.createNew(id, org, account, Provider.AWS, ARN, "web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, Map.of("environment", "prod"), "discovery", T0);
    }

    @Test
    @DisplayName("a new resource keeps what the tool said, was first and last seen now, and has no end date")
    void createNew() {
        CloudResource resource = resource();

        assertEquals(id, resource.getId());
        assertEquals(org, resource.getOrganisationId());
        assertEquals(account, resource.getAccountId());
        assertEquals(Provider.AWS, resource.getProvider());
        assertEquals(ARN, resource.getExternalId());
        assertEquals("web-1", resource.getName());
        assertEquals(Kind.COMPUTE, resource.getKind());
        assertEquals("us-east-1", resource.getRegion());
        assertEquals(State.RUNNING, resource.getState());
        assertEquals(Map.of("environment", "prod"), resource.getTags());
        assertEquals(T0, resource.getFirstSeenAt());
        assertEquals(T0, resource.getLastSeenAt());
        assertEquals(T0, resource.getUpdatedAt());
        assertNull(resource.getPurgeAt());
    }

    @Test
    @DisplayName("an Azure resource id (slashes, a leading slash) and no tags are valid")
    void azure() {
        CloudResource resource = CloudResource.createNew(id, org, account, Provider.AZURE, AZURE, "vm1", Kind.COMPUTE, "brazilsouth", State.STOPPED, null, "discovery", T0);

        assertEquals(AZURE, resource.getExternalId());
        assertTrue(resource.getTags().isEmpty());
    }

    @Test
    @DisplayName("what is mandatory, and what is an identifier, is checked")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(null, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, null, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, null, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, null, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, null, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, "has space", "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, null, Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "has space", Kind.COMPUTE, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", null, "us-east-1", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", null, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, null, State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "US East", State.RUNNING, null, "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, " ", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING, null, null, T0));
    }

    @Test
    @DisplayName("only four tag keys are kept, and a tag value is an identifier: a person's name or a secret has no place to go")
    void tags() {
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING,
                Map.of("password", "x"), "x", T0));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING,
                Map.of("owner", "Ana Souza <ana@example.com>"), "x", T0));
        Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("owner", null);
        assertThrows(IllegalArgumentException.class, () -> CloudResource.createNew(id, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "us-east-1", State.RUNNING,
                nullValue, "x", T0));
    }

    @Test
    @DisplayName("restating with the same facts changes nothing but the moment seen, which only moves forward")
    void restateSame() {
        CloudResource resource = resource();
        Instant later = T0.plusSeconds(3600);

        assertFalse(resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, Map.of("environment", "prod"), later));
        assertEquals(later, resource.getLastSeenAt());
        assertEquals(T0, resource.getUpdatedAt());
        assertFalse(resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, Map.of("environment", "prod"), T0.minusSeconds(60)));
        assertEquals(later, resource.getLastSeenAt());
    }

    @Test
    @DisplayName("restating with something different replaces what was kept and answers that it changed; no tags means none")
    void restateChanged() {
        CloudResource resource = resource();
        Instant later = T0.plusSeconds(60);

        assertTrue(resource.restate("web-2", Kind.COMPUTE, "us-east-1", State.RUNNING, Map.of("environment", "prod"), later));
        assertEquals("web-2", resource.getName());
        assertEquals(later, resource.getUpdatedAt());
        assertTrue(resource.restate("web-2", Kind.DATABASE, "us-east-1", State.RUNNING, Map.of("environment", "prod"), later));
        assertTrue(resource.restate("web-2", Kind.DATABASE, "eu-west-1", State.RUNNING, Map.of("environment", "prod"), later));
        assertTrue(resource.restate("web-2", Kind.DATABASE, "eu-west-1", State.STOPPED, Map.of("environment", "prod"), later));
        assertTrue(resource.restate("web-2", Kind.DATABASE, "eu-west-1", State.STOPPED, null, later));
        assertTrue(resource.getTags().isEmpty());
        assertFalse(resource.restate("web-2", Kind.DATABASE, "eu-west-1", State.STOPPED, null, later));
        assertThrows(IllegalArgumentException.class, () -> resource.restate("bad name", Kind.DATABASE, "eu-west-1", State.STOPPED, null, later));
    }

    @Test
    @DisplayName("a terminated resource ends after the retention counted from when it was last seen; any other has no end")
    void expiry() {
        CloudResource resource = resource();

        resource.expireAfter(Duration.ofDays(30));
        assertNull(resource.getPurgeAt());
        resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.TERMINATED, null, T0.plusSeconds(60));
        resource.expireAfter(Duration.ofDays(30));
        assertEquals(T0.plusSeconds(60).plus(Duration.ofDays(30)), resource.getPurgeAt());
        resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.RUNNING, null, T0.plusSeconds(120));
        resource.expireAfter(Duration.ofDays(30));
        assertNull(resource.getPurgeAt());
    }

    @Test
    @DisplayName("judged when read: present while seen lately, missing after the allowed silence, gone once terminated")
    void assess() {
        CloudResource resource = resource();
        Duration allowed = Duration.ofHours(48);

        assertEquals(Presence.PRESENT, resource.assess(T0.plus(allowed), allowed));
        assertEquals(Presence.MISSING, resource.assess(T0.plus(allowed).plusSeconds(1), allowed));
        resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.TERMINATED, null, T0.plusSeconds(60));
        assertEquals(Presence.GONE, resource.assess(T0.plusSeconds(61), allowed));
    }

    @Test
    @DisplayName("reconstituting needs everything that identifies and describes the resource")
    void reconstitute() {
        CloudResource back = CloudResource.reconstitute(id, org, account, Provider.AZURE, AZURE, "vm1", Kind.NETWORK, "brazilsouth", State.STOPPED, null, T0, T0, T0, T0);

        assertEquals(Provider.AZURE, back.getProvider());
        assertTrue(back.getTags().isEmpty());
        assertEquals(T0, back.getPurgeAt());
        UUID x = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(null, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, null, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, null, Provider.AWS, ARN, "n", Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, null, ARN, "n", Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, Provider.AWS, null, "n", Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, Provider.AWS, ARN, null, Kind.COMPUTE, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, Provider.AWS, ARN, "n", null, "r1", State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, null, State.RUNNING, null, T0, T0, T0, null));
        assertThrows(IllegalArgumentException.class, () -> CloudResource.reconstitute(x, org, account, Provider.AWS, ARN, "n", Kind.COMPUTE, "r1", null, null, T0, T0, T0, null));
    }

    @Test
    @DisplayName("the promise is broken (CLOUD_RESOURCE_MISSING) when the resource is missing, and kept when present or gone; its status is TERMINATED once terminated (platform ADR-001)")
    void promise() {
        assertEquals(Promise.broken("CLOUD_RESOURCE_MISSING"), CloudResource.promise(Presence.MISSING));
        assertEquals(Promise.kept(), CloudResource.promise(Presence.PRESENT));
        assertEquals(Promise.kept(), CloudResource.promise(Presence.GONE));
        assertEquals("ACTIVE", resource().promiseStatus());
        CloudResource terminated = resource();
        terminated.restate("web-1", Kind.COMPUTE, "us-east-1", State.TERMINATED, null, T0.plusSeconds(60));
        assertEquals("TERMINATED", terminated.promiseStatus());
        assertEquals(Promise.Verdict.BROKEN, Promise.broken("X").verdict());
        assertEquals("X", Promise.broken("X").code());
    }

    @Test
    @DisplayName("seen again after being missing it changed, though nothing in its description did; the moment only moves forward")
    void markChanged() {
        CloudResource resource = resource();

        resource.markChanged(T0.plusSeconds(3600));
        assertEquals(T0.plusSeconds(3600), resource.getUpdatedAt());
        resource.markChanged(T0.plusSeconds(60));
        assertEquals(T0.plusSeconds(3600), resource.getUpdatedAt());
    }
}
