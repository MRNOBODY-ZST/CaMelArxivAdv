package com.camel_hub.advertisement.arxiv.client;

import reactor.core.publisher.Mono;

public interface GlobalArxivRateLease {

	Mono<Void> awaitPermit();
}
