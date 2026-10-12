package com.camel_hub.advertisement.campaign;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.MismatchedInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Optional explicit selection; omission of the entire body retains segment-wide generation. */
@JsonDeserialize(using = CampaignPersonalizationRequest.Deserializer.class)
public record CampaignPersonalizationRequest(List<UUID> paperIds, Integer recipientLimit, List<UUID> paperContactIds) {
	public CampaignPersonalizationRequest(List<UUID> paperIds, Integer recipientLimit) { this(paperIds, recipientLimit, null); }
	public CampaignPersonalizationRequest(List<UUID> paperIds) { this(paperIds, null); }

	/** Refuse coercion, duplicate fields and unknown selectors at the HTTP boundary. */
	public static final class Deserializer extends ValueDeserializer<CampaignPersonalizationRequest> {
		@Override
		public CampaignPersonalizationRequest deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.currentToken() != JsonToken.START_OBJECT) throw invalid(parser);
			List<UUID> papers = null;
			Integer limit = null;
			boolean paperContact = false;
			int fields = 0;
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				if (parser.currentToken() != JsonToken.PROPERTY_NAME || ++fields > 1) throw invalid(parser);
				String name = parser.currentName();
				JsonToken token = parser.nextToken();
				if ("recipientLimit".equals(name)) {
					if (token != JsonToken.VALUE_NUMBER_INT) throw invalid(parser);
					limit = parser.getIntValue();
					if (limit < 1 || limit > 120) throw invalid(parser);
				} else if ("paperIds".equals(name) || "paperContactIds".equals(name)) {
					paperContact = "paperContactIds".equals(name);
					if (token != JsonToken.START_ARRAY) throw invalid(parser);
					papers = new ArrayList<>();
					while (parser.nextToken() != JsonToken.END_ARRAY) {
						if (parser.currentToken() != JsonToken.VALUE_STRING || papers.size() >= (paperContact ? 100 : 20)) throw invalid(parser);
						UUID id;
						try { id = UUID.fromString(parser.getString()); }
						catch (IllegalArgumentException exception) { throw invalid(parser); }
						if (!id.toString().equalsIgnoreCase(parser.getString()) || papers.contains(id)) throw invalid(parser);
						papers.add(id);
					}
					if (papers.isEmpty()) throw invalid(parser);
				} else throw invalid(parser);
			}
			if (fields != 1) throw invalid(parser);
			return new CampaignPersonalizationRequest(paperContact || papers == null ? null : List.copyOf(papers), limit,
					paperContact ? List.copyOf(papers) : null);
		}

		private MismatchedInputException invalid(JsonParser parser) {
			return MismatchedInputException.from(parser, CampaignPersonalizationRequest.class,
					"Provide one selector: 1–20 distinct paperIds, 1–100 distinct paperContactIds, or an integer recipientLimit between 1 and 120");
		}
	}
}
