package com.camel_hub.advertisement.email.template;

import reactor.core.publisher.Mono;

public interface TemplateAssetObjectStore {
	Mono<Void> put(String objectKey, String contentType, byte[] bytes);
	Mono<byte[]> get(String objectKey);
	Mono<Void> remove(String objectKey);
}
