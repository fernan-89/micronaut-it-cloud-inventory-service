package com.thinklab.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Aggregate Root (BIAN Control Record) of the IT Cloud Inventory Service Domain: one resource a cloud account runs, as the discovery tool last
 * said it was (ADR-030). It is a snapshot, not a history: the provider's own id says which resource it is, so the same resource reported again is
 * the same resource, restated. The provider (AWS or Azure), the kind and the state come from fixed lists; the region and the tag values are
 * identifiers, never free text; only four tag keys are kept (ADR-031). The resource is judged when read (ADR-032): present, missing when the tool
 * has not seen it for longer than the allowed time, or gone when the tool said it was terminated.
 */
public class CloudResource {

    public static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9/][A-Za-z0-9._:/-]{0,299}");
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    public static final Pattern REGION = Pattern.compile("[a-z0-9-]{2,40}");
    public static final Pattern TAG_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}");
    /** The only tag keys kept: what a tag could otherwise hold (a person's name, a secret) is left out by design. */
    public static final Set<String> TAG_KEYS = Set.of("environment", "owner", "cost-center", "application");

    private final UUID id;
    private final UUID organisationId;
    private final UUID accountId;
    private final Provider provider;
    private final String externalId;
    private String name;
    private Kind kind;
    private String region;
    private State state;
    private Map<String, String> tags;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant updatedAt;
    private Instant purgeAt;

    private CloudResource(UUID id, UUID organisationId, UUID accountId, Provider provider, String externalId, String name, Kind kind, String region, State state,
                          Map<String, String> tags, Instant firstSeenAt, Instant lastSeenAt, Instant updatedAt, Instant purgeAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.accountId = accountId;
        this.provider = provider;
        this.externalId = externalId;
        this.name = name;
        this.kind = kind;
        this.region = region;
        this.state = state;
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.updatedAt = updatedAt;
        this.purgeAt = purgeAt;
    }

    public static CloudResource createNew(UUID id, UUID organisationId, UUID accountId, Provider provider, String externalId, String name, Kind kind, String region,
                                          State state, Map<String, String> tags, String executor, Instant now) {
        if (id == null || organisationId == null || accountId == null) {
            throw new IllegalArgumentException("ID, Organisation ID and Account ID are mandatory for a CloudResource.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("The provider of the resource is mandatory.");
        }
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            throw new IllegalArgumentException("The provider's id for the resource must be up to 300 letters, digits, dots, dashes, underscores, colons or slashes.");
        }
        validate(name, kind, region, state, tags);
        requireExecutor(executor);
        return new CloudResource(id, organisationId, accountId, provider, externalId, name, kind, region, state, tags, now, now, now, null);
    }

    public static CloudResource reconstitute(UUID id, UUID organisationId, UUID accountId, Provider provider, String externalId, String name, Kind kind, String region,
                                             State state, Map<String, String> tags, Instant firstSeenAt, Instant lastSeenAt, Instant updatedAt, Instant purgeAt) {
        if (id == null || organisationId == null || accountId == null || provider == null || externalId == null || name == null || kind == null || region == null || state == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Account ID, Provider, External ID, Name, Kind, Region and State are mandatory to reconstitute a CloudResource.");
        }
        return new CloudResource(id, organisationId, accountId, provider, externalId, name, kind, region, state, tags, firstSeenAt, lastSeenAt, updatedAt, purgeAt);
    }

    // --- Domain Behaviors ---

    /**
     * The tool saw the resource again and says what it looks like now (the same provider, account and id): what the tool says replaces what was
     * kept, and the moment it was seen only ever moves forward. Answers whether anything but that moment changed.
     */
    public boolean restate(String newName, Kind newKind, String newRegion, State newState, Map<String, String> newTags, Instant now) {
        validate(newName, newKind, newRegion, newState, newTags);
        Map<String, String> sameOrder = newTags != null ? newTags : Map.of();
        boolean changed = !newName.equals(name) || newKind != kind || !newRegion.equals(region) || newState != state || !sameOrder.equals(tags);
        this.name = newName;
        this.kind = newKind;
        this.region = newRegion;
        this.state = newState;
        this.tags = new LinkedHashMap<>(sameOrder);
        if (now.isAfter(lastSeenAt)) {
            this.lastSeenAt = now;
        }
        this.updatedAt = changed ? now : updatedAt;
        return changed;
    }

    /** A terminated resource leaves the store {@code retention} after the tool last saw it; any other has no end (ADR-033). */
    public void expireAfter(Duration retention) {
        this.purgeAt = state == State.TERMINATED ? lastSeenAt.plus(retention) : null;
    }

    /** Where the resource stands at {@code now} (ADR-032): GONE when terminated, MISSING when not seen for longer than {@code staleAfter}, PRESENT otherwise. */
    public Presence assess(Instant now, Duration staleAfter) {
        if (state == State.TERMINATED) {
            return Presence.GONE;
        }
        return now.isAfter(lastSeenAt.plus(staleAfter)) ? Presence.MISSING : Presence.PRESENT;
    }

    // --- Internal helpers ---

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory to report a CloudResource.");
        }
    }

    private static void validate(String name, Kind kind, String region, State state, Map<String, String> tags) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("The name must be up to 128 letters, digits, dots, dashes, underscores, colons or slashes.");
        }
        if (kind == null || state == null) {
            throw new IllegalArgumentException("The kind and the state of the resource are mandatory.");
        }
        if (region == null || !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("The region must be 2 to 40 lowercase letters, digits or dashes.");
        }
        if (tags != null) {
            tags.forEach((key, value) -> {
                if (!TAG_KEYS.contains(key)) {
                    throw new IllegalArgumentException("Only these tags are kept: " + String.join(", ", new TreeSet<>(TAG_KEYS)) + ".");
                }
                if (value == null || !TAG_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("A tag value must be up to 64 letters, digits, dots, dashes, underscores, colons or slashes.");
                }
            });
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAccountId() { return accountId; }
    public Provider getProvider() { return provider; }
    public String getExternalId() { return externalId; }
    public String getName() { return name; }
    public Kind getKind() { return kind; }
    public String getRegion() { return region; }
    public State getState() { return state; }
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPurgeAt() { return purgeAt; }

    // --- Nested Value Objects ---

    /** Only the two clouds this registry knows (ADR-030): another is a later decision, not a free-text value. */
    public enum Provider { AWS, AZURE }

    public enum Kind { COMPUTE, DATABASE, STORAGE, NETWORK, CONTAINER, SERVERLESS, OTHER }

    public enum State { RUNNING, STOPPED, TERMINATED }

    /** GONE: the tool said it is terminated. MISSING: the tool has not seen it for too long. PRESENT: seen lately and not terminated. */
    public enum Presence { PRESENT, MISSING, GONE }
}
