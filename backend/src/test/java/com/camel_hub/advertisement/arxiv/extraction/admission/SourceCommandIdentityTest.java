package com.camel_hub.advertisement.arxiv.extraction.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class SourceCommandIdentityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PAYLOAD = """
        {"parserVersion":"0.1.0","targets":[{"paperId":"11111111-1111-1111-1111-111111111111","arxivId":"2608.00001"}]}
        """;

    @Test void literalCanonicalVectors() throws Exception {
        var fixture = JSON.readTree(Path.of("../contracts/fixtures/source-command-identity-v1.json").toFile());
        for (var vector : fixture.get("canonical")) {
            byte[] encoded = SourceCommandIdentity.canonicalEncode(vector.get("value"));
            assertThat(HexFormat.of().formatHex(encoded)).isEqualTo(vector.get("hex").asText());
            assertThat(SourceCommandIdentity.sha256(encoded)).isEqualTo(vector.get("sha256").asText());
        }
        for (var vector : fixture.get("payloads")) {
            var payload=vector.get("payload").toString();
            assertThat(HexFormat.of().formatHex(SourceCommandIdentity.canonicalEncode(SourceCommandIdentity.validatedPayload(payload)))).isEqualTo(vector.get("hex").asText());
            assertThat(SourceCommandIdentity.fingerprint(payload)).isEqualTo(vector.get("sha256").asText());
        }
    }

    @Test void defaultsAreExplicitAndParserOrderAndUnknownFieldsBound() {
        String explicit = PAYLOAD.replace("\"arxivId\"", "\"metadataAuthors\":[],\"arxivId\"");
        assertThat(SourceCommandIdentity.fingerprint(PAYLOAD)).isEqualTo(SourceCommandIdentity.fingerprint(explicit));
        assertThat(SourceCommandIdentity.fingerprint(PAYLOAD)).isNotEqualTo(SourceCommandIdentity.fingerprint(PAYLOAD.replace("0.1.0", "0.2.0")));
        assertThatThrownBy(() -> SourceCommandIdentity.fingerprint(PAYLOAD.replace("\"parserVersion\"", "\"unknown\":true,\"parserVersion\""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceCommandIdentity.fingerprint(PAYLOAD.replace("\"arxivId\"", "\"metadataAuthors\":null,\"arxivId\""))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsDuplicateFieldsFloatsAndUnpairedSurrogates() throws Exception {
        assertThatThrownBy(() -> SourceCommandIdentity.fingerprint(PAYLOAD+" {}" )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceCommandIdentity.fingerprint(PAYLOAD.replace("\"parserVersion\"", "\"parserVersion\":\"x\",\"parserVersion\""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceCommandIdentity.canonicalEncode(JSON.readTree("1.0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceCommandIdentity.canonicalEncode(JSON.getNodeFactory().textNode("\ud800"))).isInstanceOf(IllegalArgumentException.class);
    }
}
