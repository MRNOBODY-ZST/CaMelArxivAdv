package com.camel_hub.advertisement.campaign;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class PaperContactSelectorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void acceptsExplicitPaperContactManifest() {
        assertThat(mapper.readValue("{\"paperContactIds\":[\"30000000-0000-0000-0000-000000000001\"]}",
                CampaignPersonalizationRequest.class)).isNotNull();
    }

    @Test void rejectsCoercionDuplicatesMixedModesAndOversizedManifest() {
        String id = "\"30000000-0000-0000-0000-000000000001\"";
        for (String body : new String[]{"{}", "{\"paperContactIds\":null}", "{\"paperContactIds\":[]}",
                "{\"paperContactIds\":[42]}", "{\"paperContactIds\":[\"bad\"]}",
                "{\"paperContactIds\":[" + id + "," + id + "]}",
                "{\"paperContactIds\":[" + id + "],\"paperContactIds\":[" + id + "]}",
                "{\"paperContactIds\":[" + id + "],\"recipientLimit\":1}",
                "{\"paperContactIds\":[" + id + "],\"paperIds\":[" + id + "]}",
                "{\"unknown\":[" + id + "]}",
                "{\"paperContactIds\":[" + IntStream.range(0, 101).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(Collectors.joining(",")) + "]}"}) {
            assertThatThrownBy(() -> mapper.readValue(body, CampaignPersonalizationRequest.class)).as(body)
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
