package com.camel_hub.advertisement.email.template;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MinioTemplateAssetObjectStore implements TemplateAssetObjectStore {

	private final MinioClient client;
	private final String bucket;
	private final AtomicBoolean initialized = new AtomicBoolean(false);

	public MinioTemplateAssetObjectStore(TemplateAssetProperties properties) {
		this.client = MinioClient.builder().endpoint(properties.endpoint())
				.credentials(properties.accessKey(), properties.secretKey()).build();
		this.bucket = properties.bucket();
	}

	@Override
	public Mono<Void> put(String objectKey, String contentType, byte[] bytes) {
		return blocking(() -> {
			ensureBucket();
			client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
					.contentType(contentType).stream(new ByteArrayInputStream(bytes), (long) bytes.length, -1L).build());
			return null;
		}).then();
	}

	@Override
	public Mono<byte[]> get(String objectKey) {
		return blocking(() -> {
			ensureBucket();
			try (InputStream input = client.getObject(
					GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
				return input.readAllBytes();
			}
		});
	}

	@Override
	public Mono<Void> remove(String objectKey) {
		return blocking(() -> {
			ensureBucket();
			client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
			return null;
		}).then();
	}

	private synchronized void ensureBucket() throws Exception {
		if (initialized.get()) return;
		if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
			client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
		}
		initialized.set(true);
	}

	private <T> Mono<T> blocking(CheckedSupplier<T> supplier) {
		return Mono.fromCallable(supplier::get).subscribeOn(Schedulers.boundedElastic());
	}

	@FunctionalInterface
	private interface CheckedSupplier<T> {
		T get() throws Exception;
	}
}
