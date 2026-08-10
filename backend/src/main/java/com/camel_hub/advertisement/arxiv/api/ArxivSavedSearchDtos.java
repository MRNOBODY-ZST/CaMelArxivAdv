package com.camel_hub.advertisement.arxiv.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ArxivSavedSearchDtos {

	private ArxivSavedSearchDtos() {
	}

	public record UpsertRequest(
			@NotBlank @Size(max = 160) String name,
			@NotNull @Valid ArxivSearchDtos.PreviewRequest criteria
	) {
	}
}
