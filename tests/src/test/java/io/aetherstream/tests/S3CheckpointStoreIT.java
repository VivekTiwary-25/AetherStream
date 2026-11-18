package io.aetherstream.tests;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherstream.common.protocol.CheckpointManifest;
import io.aetherstream.engine.storage.S3CheckpointStore;
import io.aetherstream.tests.support.IntegrationInfrastructure;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class S3CheckpointStoreIT {
    @Test
    void roundTripsCheckpointManifestAndReportsObjectExistence() {
        S3CheckpointStore store = new S3CheckpointStore(IntegrationInfrastructure.s3Endpoint(), "aether",
                "aether-secret", IntegrationInfrastructure.BUCKET);
        String key = "it/checkpoints/manifest.json";
        CheckpointManifest manifest = new CheckpointManifest("exec-s3", 12, 4, "pipeline-it", 2, 1,
                "2026-11-19T12:00:00Z", Map.of(0, new CheckpointManifest.PartitionEntry(71, "worker-1.json"),
                        2, new CheckpointManifest.PartitionEntry(108, "worker-2.json")));

        assertThat(store.exists(key)).isFalse();
        store.putJson(key, manifest);

        assertThat(store.exists(key)).isTrue();
        assertThat(store.getJson(key, CheckpointManifest.class)).isEqualTo(manifest);
        assertThat(store.getJson("it/checkpoints/missing.json", CheckpointManifest.class)).isNull();
    }
}
