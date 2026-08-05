package com.camel_hub.advertisement.common.api;

import java.util.List;
import java.util.Map;

public record ApiError(
		String type,
		String title,
		int status,
		String detail,
		String instance,
		String traceId,
		Map<String, List<String>> fieldErrors
) {
	public ApiError {
		fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
	}
}

