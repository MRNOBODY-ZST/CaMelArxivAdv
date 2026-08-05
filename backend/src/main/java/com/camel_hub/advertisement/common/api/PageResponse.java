package com.camel_hub.advertisement.common.api;

import java.util.List;

public record PageResponse<T>(
		List<T> items,
		int page,
		int pageSize,
		long total,
		int totalPages
) {
	public PageResponse {
		items = List.copyOf(items);
	}

	public static <T> PageResponse<T> of(List<T> items, int page, int pageSize, long total) {
		int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
		return new PageResponse<>(items, page, pageSize, total, totalPages);
	}
}
