package com.camel_hub.advertisement.job.service;

import reactor.core.publisher.Mono;

import java.util.UUID;

@FunctionalInterface
public interface JobControlSignal {

	Mono<Void> set(UUID jobId, String control);

	static JobControlSignal noop() {
		return (jobId, control) -> Mono.empty();
	}
}
