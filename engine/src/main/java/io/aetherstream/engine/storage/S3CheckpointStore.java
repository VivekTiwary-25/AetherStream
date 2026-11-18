package io.aetherstream.engine.storage;

import io.aetherstream.common.Json;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** S3 (MinIO) checkpoint store. Creates the bucket if it is missing. */
public class S3CheckpointStore implements CheckpointStore {
    private final MinioClient client;
    private final String bucket;

    public S3CheckpointStore(String endpoint, String accessKey, String secretKey, String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        ensureBucket();
    }

    /** Uses S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY, S3_BUCKET (contract 00 defaults). */
    public static S3CheckpointStore fromEnv() {
        return new S3CheckpointStore(
                env("S3_ENDPOINT", "http://minio:9000"),
                env("S3_ACCESS_KEY", "aether"),
                env("S3_SECRET_KEY", "aether-secret"),
                env("S3_BUCKET", "aether-checkpoints"));
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (ErrorResponseException e) {
            String code = errorCode(e);
            if (!"BucketAlreadyOwnedByYou".equals(code) && !"BucketAlreadyExists".equals(code)) {
                throw new StorageException("cannot create bucket " + bucket, e);
            }
        } catch (Exception e) {
            throw new StorageException("cannot create bucket " + bucket, e);
        }
    }

    @Override
    public void putJson(String key, Object value) {
        try {
            byte[] bytes = Json.MAPPER.writeValueAsBytes(value);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception e) {
            throw new StorageException("cannot put " + key, e);
        }
    }

    @Override
    public <T> T getJson(String key, Class<T> type) {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return Json.MAPPER.readValue(in, type);
        } catch (ErrorResponseException e) {
            if (isMissing(e)) {
                return null;
            }
            throw new StorageException("cannot get " + key, e);
        } catch (Exception e) {
            throw new StorageException("cannot get " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if (isMissing(e)) {
                return false;
            }
            throw new StorageException("cannot stat " + key, e);
        } catch (Exception e) {
            throw new StorageException("cannot stat " + key, e);
        }
    }

    private static String errorCode(ErrorResponseException e) {
        return e.errorResponse() == null ? null : e.errorResponse().code();
    }

    private static boolean isMissing(ErrorResponseException e) {
        String code = errorCode(e);
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }
}
