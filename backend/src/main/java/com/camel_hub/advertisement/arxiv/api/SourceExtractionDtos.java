package com.camel_hub.advertisement.arxiv.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class SourceExtractionDtos {

	private SourceExtractionDtos() {
	}

	public record BatchRequest(
			@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> paperIds
	) { }
}
