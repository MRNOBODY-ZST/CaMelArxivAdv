package com.camel_hub.advertisement.arxiv.search;

import com.camel_hub.advertisement.arxiv.client.AtomFeed;
import reactor.core.publisher.Mono;

public interface ArxivPreviewCache {

	Mono<AtomFeed> get(String key);

	Mono<Void> put(String key, AtomFeed value);
}
