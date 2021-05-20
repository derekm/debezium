/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.pravega;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.enterprise.event.Observes;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import io.debezium.server.TestConfigSource;
import io.debezium.server.events.ConnectorCompletedEvent;
import io.debezium.server.events.ConnectorStartedEvent;
import io.debezium.testing.testcontainers.PostgresTestResourceLifecycleManager;
import io.debezium.util.Testing;
import io.pravega.client.ClientConfig;
import io.pravega.client.EventStreamClientFactory;
import io.pravega.client.admin.ReaderGroupManager;
import io.pravega.client.admin.StreamManager;
import io.pravega.client.stream.EventStreamReader;
import io.pravega.client.stream.ReaderConfig;
import io.pravega.client.stream.ReaderGroupConfig;
import io.pravega.client.stream.ScalingPolicy;
import io.pravega.client.stream.Stream;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.UTF8StringSerializer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(PostgresTestResourceLifecycleManager.class)
@QuarkusTestResource(PravegaTestResource.class)
public class PravegaTxnIT {

    private static final int MESSAGE_COUNT = 4;
    protected static final String STREAM_NAME = "testc.inventory.customers";

    static EventStreamReader<String> reader;

    {
        Testing.Files.delete(TestConfigSource.OFFSET_STORE_PATH);
        Testing.Files.createTestingFile(TestConfigSource.OFFSET_STORE_PATH);
    }

    /**
     * Creates a reader where scope name, stream name and reader group name are STREAM_NAME.
     */
    void setupDependencies(@Observes ConnectorStartedEvent event) throws IOException {
        Testing.Print.enable();

        URI controllerURI = URI.create(PravegaTestResource.getControllerUri());
        ClientConfig clientConfig = ClientConfig.builder()
                .controllerURI(controllerURI)
                .build();
        ReaderGroupConfig readerGroupConfig = ReaderGroupConfig.builder()
                .stream(Stream.of(STREAM_NAME, STREAM_NAME))
                .disableAutomaticCheckpoints()
                .build();
        try (final StreamManager streamManager = StreamManager.create(controllerURI)) {
            streamManager.createScope(STREAM_NAME);
            StreamConfiguration streamConfig = StreamConfiguration.builder()
                    .scalingPolicy(ScalingPolicy.fixed(1))
                    .build();
            streamManager.createStream(STREAM_NAME, STREAM_NAME, streamConfig);
        }

        try (final ReaderGroupManager readerGroupManager = ReaderGroupManager.withScope(STREAM_NAME, clientConfig)) {
            readerGroupManager.createReaderGroup(STREAM_NAME, readerGroupConfig);
        }

        ReaderConfig readerConfig = ReaderConfig.builder().build();
        reader = EventStreamClientFactory.withScope(STREAM_NAME, clientConfig)
                .createReader("0", STREAM_NAME, new UTF8StringSerializer(), readerConfig);
    }

    void connectorCompleted(@Observes ConnectorCompletedEvent event) throws Exception {
        if (!event.isSuccess()) {
            throw new RuntimeException(event.getError().get());
        }
    }

    @Test
    public void testPravega() throws Exception {
        final List<String> records = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(TestConfigSource.waitForSeconds())).until(() -> {
            records.add(reader.readNextEvent(2000).getEvent());
            return records.size() >= MESSAGE_COUNT;
        });
    }

}
