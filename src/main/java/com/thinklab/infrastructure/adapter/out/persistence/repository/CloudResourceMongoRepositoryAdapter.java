package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateResourceException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.domain.repository.CloudResourceRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudResourceDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudResourceDocument.CloudResourcePersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the CloudResource aggregate, raw reactive-streams driver. The unique
 * {@code (organisationId, provider, accountId, externalId)} index is the atomic backstop of "the same resource reported twice is one resource"
 * (ADR-030); the moment a resource was last seen only ever moves forward (a {@code max}), whatever the order of the reports (ADR-033).
 */
@Singleton
public class CloudResourceMongoRepositoryAdapter implements CloudResourceRepository {

    static final String DEFAULT_DATABASE = "thinklab_it_cloud_inventory_db";
    static final String COLLECTION_NAME = "cloud_resources";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public CloudResourceMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<CloudResourceDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, CloudResourceDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<CloudResource> create(CloudResource resource) {
        return Mono.from(getCollection().insertOne(CloudResourcePersistenceMapper.toDocument(resource)))
                .map(result -> resource)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateResourceException("The resource " + resource.getExternalId() + " is already in the inventory.") : error);
    }

    @Override
    public Mono<Void> save(CloudResource resource) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, resource.getId()), Filters.eq(FIELD_ORGANISATION, resource.getOrganisationId()));
        Bson update = Updates.combine(
                Updates.set("name", resource.getName()),
                Updates.set("kind", resource.getKind().name()),
                Updates.set("region", resource.getRegion()),
                Updates.set("state", resource.getState().name()),
                Updates.set("tags", resource.getTags()),
                Updates.set("updatedAt", resource.getUpdatedAt()),
                Updates.set("purgeAt", resource.getPurgeAt()),
                Updates.max("lastSeenAt", resource.getLastSeenAt())
        );
        return Mono.from(getCollection().updateOne(guard, update)).then();
    }

    @Override
    public Mono<CloudResource> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(CloudResourcePersistenceMapper::toDomain);
    }

    @Override
    public Mono<CloudResource> findByKey(UUID organisationId, Provider provider, UUID accountId, String externalId) {
        Bson filter = Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq("provider", provider.name()), Filters.eq("accountId", accountId),
                Filters.eq("externalId", externalId));
        return Mono.from(getCollection().find(filter).first()).map(CloudResourcePersistenceMapper::toDomain);
    }

    @Override
    public Flux<CloudResource> findChangedSince(UUID organisationId, Instant since, int limit) {
        return Flux.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.gte("updatedAt", since))).sort(Sorts.descending("updatedAt")).limit(limit))
                .map(CloudResourcePersistenceMapper::toDomain);
    }

    @Override
    public Flux<CloudResource> findAll(UUID organisationId, UUID accountId, Provider provider, Kind kind, String region, State state, Presence presence, Instant missingBefore,
                                       int limit) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (accountId != null) {
            filters.add(Filters.eq("accountId", accountId));
        }
        if (provider != null) {
            filters.add(Filters.eq("provider", provider.name()));
        }
        if (kind != null) {
            filters.add(Filters.eq("kind", kind.name()));
        }
        if (region != null) {
            filters.add(Filters.eq("region", region));
        }
        if (state != null) {
            filters.add(Filters.eq("state", state.name()));
        }
        if (presence == Presence.GONE) {
            filters.add(Filters.eq("state", State.TERMINATED.name()));
        } else if (presence != null) {
            filters.add(Filters.ne("state", State.TERMINATED.name()));
            filters.add(presence == Presence.MISSING ? Filters.lt("lastSeenAt", missingBefore) : Filters.gte("lastSeenAt", missingBefore));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.descending("lastSeenAt")).limit(limit)).map(CloudResourcePersistenceMapper::toDomain);
    }
}
