package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates the indexes at startup. The UNIQUE one is the atomic backstop (ADR-033): one resource per {@code (organisation, provider, account,
 * the provider's id)}, so the same resource reported twice, even at once, is one resource (ADR-030). The list index serves the tenant's list, and a
 * TTL index on {@code purgeAt} (set only on a terminated resource) removes it once the retention has passed. Fail-open: {@code createIndex} is
 * idempotent, a failure is logged and the application still starts. Turn it off with {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class InventoryIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String RESOURCE_KEY_INDEX = "organisationId_1_provider_1_accountId_1_externalId_1";
    static final String RESOURCE_LIST_INDEX = "organisationId_1_lastSeenAt_-1";
    static final String RESOURCE_EXPIRY_INDEX = "purgeAt_1";

    private static final Logger log = LoggerFactory.getLogger(InventoryIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public InventoryIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    InventoryIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : CloudResourceMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        String resources = CloudResourceMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(resources, RESOURCE_KEY_INDEX, new Document("organisationId", 1).append("provider", 1).append("accountId", 1).append("externalId", 1),
                new IndexOptions().unique(true));
        ensureIndex(resources, RESOURCE_LIST_INDEX, new Document("organisationId", 1).append("lastSeenAt", -1), new IndexOptions());
        ensureIndex(resources, RESOURCE_EXPIRY_INDEX, new Document("purgeAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
