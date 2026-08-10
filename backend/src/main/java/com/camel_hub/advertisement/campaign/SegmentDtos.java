package com.camel_hub.advertisement.campaign;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class SegmentDtos {

	private SegmentDtos() { }

	public record RuleRequest(
			@NotBlank @Size(max = 80) String field,
			@NotBlank @Size(max = 40) String operator,
			@NotNull Object value
	) {
		SegmentModels.RuleInput input() {
			return new SegmentModels.RuleInput(field, operator, value);
		}
	}

	public record CreateRequest(
			@NotBlank @Size(max = 160) String name,
			@Size(max = 500) String description,
			@NotEmpty @Size(max = 4) List<@Valid RuleRequest> rules
	) {
		SegmentService.SegmentCommand command() {
			return new SegmentService.SegmentCommand(name, description, inputs(rules));
		}
	}

	public record PreviewRequest(
			@NotEmpty @Size(max = 4) List<@Valid RuleRequest> rules
	) {
		List<SegmentModels.RuleInput> inputs() {
			return SegmentDtos.inputs(rules);
		}
	}

	private static List<SegmentModels.RuleInput> inputs(List<RuleRequest> rules) {
		return rules.stream().map(RuleRequest::input).toList();
	}
}
