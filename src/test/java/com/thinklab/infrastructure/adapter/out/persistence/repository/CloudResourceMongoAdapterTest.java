package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateResourceException;
import com.thinklab.domain.model.CloudResource;
import com.thinklab.domain.model.CloudResource.Kind;
import com.thinklab.domain.model.CloudResource.Presence;
import com.thinklab.domain.model.CloudResource.Provider;
import com.thinklab.domain.model.CloudResource.State;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudResourceDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.CloudResourceDocument.CloudResourcePersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class CloudResourceMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/inv_test";
    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    private final UUID tenant = UUID.randomUUID();
    private MongoCollection<CloudResourceDocument> collection;

    @BeforeEach
    void setUp() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        when(client.getDatabase("inv_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_cloud_inventory_db")).thenReturn(database);
        when(database.getCollection("cloud_resources", CloudResourceDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        this.client = client;
    }

    private MongoClient client;

    private CloudResource resource() {
        return CloudResource.createNew(UUID.randomUUID(), tenant, UUID.randomUUID(), Provider.AWS, "i-0abc", "web-1", Kind.COMPUTE, "us-east-1", State.RUNNING,
                Map.of("environment", "prod"), "discovery", T0);
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<CloudResourceDocument> finds(CloudResource... found) {
        FindPublisher<CloudResourceDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        when(publisher.limit(anyInt())).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<CloudResourceDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(CloudResourcePersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("a resource survives the round trip, tags, moments and end date included")
    void roundTrip() {
        CloudResource resource = resource();
        resource.restate("web-1", Kind.COMPUTE, "us-east-1", State.TERMINATED, Map.of("owner", "team-a"), T0.plusSeconds(60));
        resource.expireAfter(java.time.Duration.ofDays(30));
        CloudResourceDocument document = CloudResourcePersistenceMapper.toDocument(resource);
        CloudResource back = CloudResourcePersistenceMapper.toDomain(document);

        assertEquals("TERMINATED", document.getState());
        assertEquals("AWS", document.getProvider());
        assertEquals("COMPUTE", document.getKind());
        assertEquals("us-east-1", document.getRegion());
        assertEquals("web-1", document.getName());
        assertEquals("i-0abc", document.getExternalId());
        assertEquals(tenant, document.getOrganisationId());
        assertEquals(resource.getAccountId(), document.getAccountId());
        assertEquals(resource.getId(), document.getId());
        assertEquals(Map.of("owner", "team-a"), document.getTags());
        assertEquals(T0, document.getFirstSeenAt());
        assertEquals(T0.plusSeconds(60), document.getLastSeenAt());
        assertEquals(resource.getUpdatedAt(), document.getUpdatedAt());
        assertEquals(resource.getPurgeAt(), document.getPurgeAt());
        assertEquals(resource.getPurgeAt(), back.getPurgeAt());
        assertEquals(State.TERMINATED, back.getState());
        assertEquals(Map.of("owner", "team-a"), back.getTags());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<CloudResourcePersistenceMapper> constructor = CloudResourcePersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create answers the resource; a duplicate key is a DuplicateResourceException and any other error passes through; the URI must not be null")
    void create() {
        CloudResource resource = resource();
        when(collection.insertOne(any(CloudResourceDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))))
                .thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        CloudResourceMongoRepositoryAdapter adapter = new CloudResourceMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(resource)).expectNext(resource).verifyComplete();
        StepVerifier.create(adapter.create(resource)).expectError(DuplicateResourceException.class).verify();
        StepVerifier.create(adapter.create(resource)).expectError(MongoWriteException.class).verify();
        assertThrows(NullPointerException.class, () -> new CloudResourceMongoRepositoryAdapter(client, null));
    }

    @Test
    @DisplayName("save is scoped to the organisation and moves the moment seen only forward (a max)")
    void save() {
        CloudResource resource = resource();
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        CloudResourceMongoRepositoryAdapter adapter = new CloudResourceMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.save(resource)).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getValue().toString().contains("organisationId"));
        assertTrue(update.getValue().toString().contains("$max") && update.getValue().toString().contains("lastSeenAt"));
    }

    @Test
    @DisplayName("the lookups are scoped to the organisation; the key lookup is by provider, account and the provider's id")
    void lookups() {
        CloudResource resource = resource();
        finds(resource);
        CloudResourceMongoRepositoryAdapter adapter = new CloudResourceMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findById(resource.getId(), tenant)).assertNext(found -> assertEquals(resource.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findByKey(tenant, Provider.AWS, resource.getAccountId(), "i-0abc")).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, org.mockito.Mockito.times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
        String key = filter.getAllValues().get(1).toString();
        assertTrue(key.contains("provider") && key.contains("accountId") && key.contains("externalId") && key.contains("organisationId"));
    }

    @Test
    @DisplayName("the list is scoped to the organisation, narrowed by each filter given, most recently seen first and limited")
    void list() {
        CloudResource resource = resource();
        FindPublisher<CloudResourceDocument> publisher = finds(resource);
        CloudResourceMongoRepositoryAdapter adapter = new CloudResourceMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.findAll(tenant, null, null, null, null, null, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, resource.getAccountId(), Provider.AWS, Kind.COMPUTE, "us-east-1", State.RUNNING, 7)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, org.mockito.Mockito.times(2)).find(filter.capture());
        String bare = filter.getAllValues().get(0).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("accountId") && !bare.contains("provider") && !bare.contains("kind") && !bare.contains("region") && !bare.contains("state"));
        String full = filter.getAllValues().get(1).toString();
        assertTrue(full.contains("accountId") && full.contains("provider") && full.contains("kind") && full.contains("region") && full.contains("state"));
        verify(publisher).limit(5);
        verify(publisher).limit(7);
    }

    @Test
    @DisplayName("the presence is asked of the store: missing is not terminated and seen before the moment, present is not terminated and seen then or later, gone is terminated")
    void presenceInTheStore() {
        finds(resource());
        CloudResourceMongoRepositoryAdapter adapter = new CloudResourceMongoRepositoryAdapter(client, URI);
        Instant cutoff = Instant.parse("2026-10-08T12:00:00Z");

        StepVerifier.create(adapter.findAll(tenant, null, null, null, null, null, Presence.MISSING, cutoff, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, null, null, null, null, null, Presence.PRESENT, cutoff, 5)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, null, null, null, null, null, Presence.GONE, cutoff, 5)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection, org.mockito.Mockito.times(3)).find(filter.capture());
        String missing = filter.getAllValues().get(0).toString();
        assertTrue(missing.contains("$ne") && missing.contains("TERMINATED") && missing.contains("$lt") && missing.contains("lastSeenAt"));
        String present = filter.getAllValues().get(1).toString();
        assertTrue(present.contains("$ne") && present.contains("TERMINATED") && present.contains("$gte") && present.contains("lastSeenAt"));
        String gone = filter.getAllValues().get(2).toString();
        assertTrue(gone.contains("TERMINATED") && !gone.contains("$ne") && !gone.contains("lastSeenAt"));
    }
}
