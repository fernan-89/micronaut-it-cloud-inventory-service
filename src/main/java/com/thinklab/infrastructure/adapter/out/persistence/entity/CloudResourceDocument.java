package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Infrastructure-specific representation of the CloudResource Aggregate for MongoDB. {@code purgeAt} is what the TTL index expires a terminated resource by. */
@Introspected
public class CloudResourceDocument {

    @BsonId
    private UUID id;
    private UUID organisationId;
    private UUID accountId;
    private String provider;
    private String externalId;
    private String name;
    private String kind;
    private String region;
    private String state;
    private Map<String, String> tags;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant updatedAt;
    private Instant purgeAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPurgeAt() { return purgeAt; }
    public void setPurgeAt(Instant purgeAt) { this.purgeAt = purgeAt; }

    public static final class CloudResourcePersistenceMapper {

        private CloudResourcePersistenceMapper() { throw new UnsupportedOperationException(); }

        public static CloudResourceDocument toDocument(CloudResource resource) {
            CloudResourceDocument doc = new CloudResourceDocument();
            doc.setId(resource.getId());
            doc.setOrganisationId(resource.getOrganisationId());
            doc.setAccountId(resource.getAccountId());
            doc.setProvider(resource.getProvider().name());
            doc.setExternalId(resource.getExternalId());
            doc.setName(resource.getName());
            doc.setKind(resource.getKind().name());
            doc.setRegion(resource.getRegion());
            doc.setState(resource.getState().name());
            doc.setTags(resource.getTags());
            doc.setFirstSeenAt(resource.getFirstSeenAt());
            doc.setLastSeenAt(resource.getLastSeenAt());
            doc.setUpdatedAt(resource.getUpdatedAt());
            doc.setPurgeAt(resource.getPurgeAt());
            return doc;
        }

        public static CloudResource toDomain(CloudResourceDocument doc) {
            return CloudResource.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getAccountId(), Provider.valueOf(doc.getProvider()), doc.getExternalId(),
                    doc.getName(), Kind.valueOf(doc.getKind()), doc.getRegion(), State.valueOf(doc.getState()), doc.getTags(), doc.getFirstSeenAt(),
                    doc.getLastSeenAt(), doc.getUpdatedAt(), doc.getPurgeAt());
        }
    }
}
