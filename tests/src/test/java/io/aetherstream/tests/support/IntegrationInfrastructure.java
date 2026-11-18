package io.aetherstream.tests.support;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Shared real-service test dependencies, started once for the test JVM. */
public final class IntegrationInfrastructure {
    public static final String TOPIC = "truck-events";
    public static final String BUCKET = "aether-checkpoints";
    private static final String ACCESS_KEY = "aether";
    private static final String SECRET_KEY = "aether-secret";
    private static final IntegrationInfrastructure INSTANCE = new IntegrationInfrastructure();

    private final PostgreSQLContainer<?> postgres;
    private final KafkaContainer kafka;
    private final GenericContainer<?> minio;

    private IntegrationInfrastructure() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("aether").withUsername("aether").withPassword("aether");
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.2"));
        minio = new GenericContainer<>(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z"))
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withCommand("server", "/data", "--console-address", ":9001")
                .withExposedPorts(9000, 9001);
        postgres.start();
        kafka.start();
        minio.start();
        initializeSchema();
        initializeTopic();
        initializeBucket();
    }

    public static String jdbcUrl() {
        return INSTANCE.postgres.getJdbcUrl();
    }

    public static String bootstrap() {
        return INSTANCE.kafka.getBootstrapServers();
    }

    public static String s3Endpoint() {
        return "http://" + INSTANCE.minio.getHost() + ":" + INSTANCE.minio.getMappedPort(9000);
    }

    public static void produce(Collection<ProducerRecord<String, String>> records) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrap());
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            for (ProducerRecord<String, String> record : records) {
                try {
                    producer.send(record).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted producing integration-test records", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("cannot produce integration-test record", e.getCause());
                }
            }
            producer.flush();
        }
    }

    public static void truncateAll() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), "aether", "aether");
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE pipelines, executions, checkpoints, window_results, alerts, dead_letters, audit_log RESTART IDENTITY CASCADE");
        } catch (Exception e) {
            throw new IllegalStateException("cannot truncate integration-test schema", e);
        }
    }

    private void initializeSchema() {
        String schemaPath = System.getProperty("aether.schema");
        if (schemaPath == null || schemaPath.isBlank()) {
            throw new IllegalStateException("system property aether.schema is required");
        }
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(schemaPath)).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot initialize integration-test schema", e);
        }
    }

    private void initializeTopic() {
        try (AdminClient admin = AdminClient.create(java.util.Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 6, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot create integration-test topic", e);
        }
    }

    private void initializeBucket() {
        try {
            MinioClient client = MinioClient.builder().endpoint("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)).credentials(ACCESS_KEY, SECRET_KEY).build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot create integration-test bucket", e);
        }
    }
}
