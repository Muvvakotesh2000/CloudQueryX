package com.cloudqueryx.storage;

import com.cloudqueryx.config.AppConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class S3ProjectFileStorage {

    private final AppConfig config;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ProjectFileStorage(AppConfig config) {
        this.config = config;
        this.s3 = S3Client.builder()
                .region(Region.of(config.awsRegion()))
                .build();
        this.presigner = S3Presigner.builder()
                .region(Region.of(config.awsRegion()))
                .build();
    }

    public boolean enabled() {
        return config.s3Enabled();
    }

    public String putText(String key, String content, String contentType) {
        ensureEnabled();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .contentType(contentType != null ? contentType : "text/plain; charset=utf-8")
                .build();
        s3.putObject(request, RequestBody.fromString(content != null ? content : "", StandardCharsets.UTF_8));
        return key;
    }

    public String getText(String key) {
        ensureEnabled();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .build();
        return s3.getObjectAsBytes(request).asUtf8String();
    }

    public PresignedUpload presignPut(String key, String contentType, Duration ttl) {
        ensureEnabled();
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .contentType(contentType != null ? contentType : "text/plain; charset=utf-8")
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(ttl != null ? ttl : Duration.ofMinutes(10))
                .putObjectRequest(objectRequest)
                .build();
        var presigned = presigner.presignPutObject(presignRequest);
        return new PresignedUpload(presigned.url().toString(), key, objectRequest.contentType(),
                presigned.expiration().toString());
    }

    public String keyFor(String userId, String projectId, int version, String path) {
        String normalized = path == null ? "untitled.txt" : path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return "users/%s/projects/%s/files/v%s/%s".formatted(userId, projectId, version, normalized);
    }

    public int deleteUserPrefix(String userId) {
        if (!enabled()) return 0;
        String prefix = "users/%s/".formatted(userId);
        int deleted = 0;
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                    .bucket(config.s3Bucket())
                    .prefix(prefix);
            if (continuationToken != null) request.continuationToken(continuationToken);
            var response = s3.listObjectsV2(request.build());
            for (var object : response.contents()) {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(config.s3Bucket())
                        .key(object.key())
                        .build());
                deleted++;
            }
            continuationToken = response.nextContinuationToken();
        } while (continuationToken != null && !continuationToken.isBlank());
        return deleted;
    }

    public StorageDiagnostic diagnose(String userId) {
        ensureEnabled();
        String key = "diagnostics/%s/%s.txt".formatted(userId, java.util.UUID.randomUUID());
        String content = "cloudqueryx-s3-diagnostic";
        putText(key, content, "text/plain; charset=utf-8");
        String readBack = getText(key);
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(config.s3Bucket())
                .key(key)
                .build());
        return new StorageDiagnostic(key, content.equals(readBack));
    }

    private void ensureEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("AWS_S3_BUCKET is required for cloud project file storage");
        }
    }

    public record PresignedUpload(String uploadUrl, String s3Key, String contentType, String expiresAt) {}
    public record StorageDiagnostic(String testKey, boolean readBackMatched) {}
}
