package com.cloudqueryx.storage;

import com.cloudqueryx.config.AppConfig;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

public class S3ProjectFileStorage {

    private final AppConfig config;
    private final S3Client s3;

    public S3ProjectFileStorage(AppConfig config) {
        this.config = config;
        this.s3 = S3Client.builder()
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

    private void ensureEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("AWS_S3_BUCKET is required for cloud project file storage");
        }
    }
}
