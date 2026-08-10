package com.camel_hub.advertisement.arxiv.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TaxonomySnapshotLoaderTest {

	private final TaxonomySnapshotLoader loader = new TaxonomySnapshotLoader(
			new ObjectMapper().findAndRegisterModules());

	@Test
	void loadsTheCompleteVersionedOfflineSnapshot() {
		TaxonomySnapshot snapshot = loader.loadDefault();

		assertThat(snapshot.snapshotVersion()).isEqualTo("arxiv-taxonomy-2026-08");
		assertThat(snapshot.sourceType()).isEqualTo("OFFLINE_SNAPSHOT");
		assertThat(snapshot.sourceUrls()).contains(
				"https://arxiv.org/category_taxonomy",
				"https://oaipmh.arxiv.org/oai?verb=ListSets");
		assertThat(snapshot.payloadSha256()).matches("[0-9a-f]{64}");
		assertThat(snapshot.categories()).hasSizeGreaterThan(150);
		assertThat(snapshot.categories()).extracting(TaxonomyCategory::groupId)
				.contains("cs", "econ", "eess", "math", "physics", "q-bio", "q-fin", "stat");
		assertThat(snapshot.categories()).filteredOn(category -> category.categoryId().equals("cs.AI"))
				.singleElement()
				.satisfies(category -> {
					assertThat(category.groupName()).isEqualTo("Computer Science");
					assertThat(category.archiveId()).isEqualTo("cs");
					assertThat(category.description()).contains("areas of AI");
				});
		assertThat(snapshot.categories()).filteredOn(category -> category.categoryId().equals("cs.NA"))
				.singleElement()
				.satisfies(category -> {
					assertThat(category.alias()).isTrue();
					assertThat(category.aliasTarget()).isEqualTo("math.NA");
				});
		assertThat(new HashSet<>(snapshot.categories().stream()
				.map(TaxonomyCategory::categoryId).toList())).hasSameSizeAs(snapshot.categories());
	}

	@Test
	void rejectsDuplicateCategoriesAndAliasesWithUnknownTargets() {
		String duplicate = snapshotJson("""
				{"groupId":"cs","groupName":"Computer Science","archiveId":"cs","archiveName":"Computer Science","categoryId":"cs.AI","categoryName":"AI","description":"AI","alias":false,"aliasTarget":null},
				{"groupId":"cs","groupName":"Computer Science","archiveId":"cs","archiveName":"Computer Science","categoryId":"cs.AI","categoryName":"Duplicate","description":"AI","alias":false,"aliasTarget":null}
				""");
		String danglingAlias = snapshotJson("""
				{"groupId":"cs","groupName":"Computer Science","archiveId":"cs","archiveName":"Computer Science","categoryId":"cs.AI","categoryName":"AI","description":"AI","alias":false,"aliasTarget":null},
				{"groupId":"cs","groupName":"Computer Science","archiveId":"cs","archiveName":"Computer Science","categoryId":"cs.NA","categoryName":"NA","description":"Alias","alias":true,"aliasTarget":"math.NA"}
				""");

		assertThatIllegalArgumentException().isThrownBy(() -> load(duplicate))
				.withMessageContaining("duplicate category");
		assertThatIllegalArgumentException().isThrownBy(() -> load(danglingAlias))
				.withMessageContaining("unknown target");
	}

	private TaxonomySnapshot load(String json) {
		return loader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test-snapshot");
	}

	private String snapshotJson(String categories) {
		return """
				{
				  "snapshotVersion":"test",
				  "sourceType":"OFFLINE_SNAPSHOT",
				  "sourceUrls":["https://arxiv.org/category_taxonomy"],
				  "sourceUpdatedAt":"2026-08-05T00:00:00Z",
				  "generatedAt":"2026-08-05T00:00:00Z",
				  "payloadSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				  "categories":[%s]
				}
				""".formatted(categories);
	}
}
