package com.camel_hub.advertisement.arxiv.api;

import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class ArxivImportDtos {

	private ArxivImportDtos() {
	}

	public record ImportRequest(
			@Size(max = 10_000) List<@Pattern(
					regexp = "(?:[0-9]{4}\\.[0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})(?:v[0-9]+)?") String> arxivIds,
			@Valid ArxivSearchDtos.PreviewRequest criteria,
			@Min(1) @Max(1_000_000) Integer maxPapers
	) {
		ArxivImportService.ImportCommand command() {
			return new ArxivImportService.ImportCommand(
					arxivIds == null ? List.of() : arxivIds,
					criteria == null ? null : criteria.criteria(), maxPapers);
		}
	}

	public record OaiSyncRequest(
			@Pattern(regexp = "[A-Za-z0-9.-]{1,60}(?::[A-Za-z0-9.-]{1,60}){0,2}") String setSpec,
			LocalDate from
	) {
		ArxivImportService.OaiSyncCommand command() {
			return new ArxivImportService.OaiSyncCommand(setSpec, from);
		}
	}
}
