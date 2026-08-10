package com.camel_hub.advertisement.campaign;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentRuleTest {

	@Test
	void acceptsOnlyTheDocumentedRuleFieldsAndValues() {
		var criteria = SegmentModels.criteria(List.of(
				new SegmentModels.RuleInput("primaryCategory", "equals", TextNode.valueOf("cs.AI")),
				new SegmentModels.RuleInput("confidence", "equals", TextNode.valueOf("HIGH")),
				new SegmentModels.RuleInput("verificationStatus", "equals", TextNode.valueOf("CONFIRMED")),
				new SegmentModels.RuleInput("corresponding", "equals", BooleanNode.TRUE)));

		assertThat(criteria.primaryCategory()).isEqualTo("cs.AI");
		assertThat(criteria.confidence()).isEqualTo("HIGH");
		assertThat(criteria.verificationStatus()).isEqualTo("CONFIRMED");
		assertThat(criteria.corresponding()).isTrue();
	}

	@Test
	void rejectsUnknownDuplicateAndSqlShapedRules() {
		assertThatThrownBy(() -> SegmentModels.criteria(List.of(
				new SegmentModels.RuleInput("email_domain", "equals", TextNode.valueOf("example.org")))))
				.isInstanceOf(SegmentValidationException.class);
		assertThatThrownBy(() -> SegmentModels.criteria(List.of(
				new SegmentModels.RuleInput("confidence", "equals", TextNode.valueOf("HIGH' OR '1'='1")))))
				.isInstanceOf(SegmentValidationException.class);
		assertThatThrownBy(() -> SegmentModels.criteria(List.of(
				new SegmentModels.RuleInput("corresponding", "equals", BooleanNode.TRUE),
				new SegmentModels.RuleInput("corresponding", "equals", BooleanNode.FALSE))))
				.isInstanceOf(SegmentValidationException.class);
	}
}
