package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class InventoryIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique resource key, the list index and the expiry (TTL) index that counts from purgeAt")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> resources = mock(MongoCollection.class);
        when(client.getDatabase("tenant_inv")).thenReturn(database);
        when(database.getCollection("cloud_resources")).thenReturn(resources);
        when(resources.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new InventoryIndexInitializer(client, "mongodb://mongo:27017/tenant_inv").onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(resources, times(3)).createIndex(any(), options.capture());
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_provider_1_accountId_1_externalId_1", options.getAllValues().get(0).getName());
        assertFalse(options.getAllValues().get(1).isUnique());
        assertEquals("organisationId_1_lastSeenAt_-1", options.getAllValues().get(1).getName());
        assertEquals("purgeAt_1", options.getAllValues().get(2).getName());
        assertEquals(0L, options.getAllValues().get(2).getExpireAfter(TimeUnit.SECONDS));
        assertEquals(null, options.getAllValues().get(1).getExpireAfter(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_cloud_inventory_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        var initializer = new InventoryIndexInitializer(client, "mongodb://mongo:27017");

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(3)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new InventoryIndexInitializer(null, "mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> new InventoryIndexInitializer(client, null));
    }
}
