package com.camel_hub.advertisement.arxiv.extraction.admission;

import org.junit.jupiter.api.Test;
import static com.camel_hub.advertisement.arxiv.extraction.admission.SourceAdmissionDtos.*;
import static org.assertj.core.api.Assertions.*;

class SourceAdmissionMailboxTest {
    @Test void fullFoldMatchesPinnedPythonForEveryUnicodeScalar() throws Exception {
        var fixture=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Path.of("../contracts/fixtures/source-admission-state-v2.json").toFile()).get("unicode15_casefold_scalar_reference");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        int count=0;
        for(int codepoint=0;codepoint<=0x10ffff;codepoint++) {
            if(codepoint>=0xd800 && codepoint<=0xdfff) continue;
            byte[] folded=SourceEvidenceCaseFold.fold(new String(Character.toChars(codepoint))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(folded.length).array()); digest.update(folded); count++;
        }
        assertThat(count).isEqualTo(fixture.get("scalar_count").intValue());
        assertThat(java.util.HexFormat.of().formatHex(digest.digest())).isEqualTo(fixture.get("sha256").asText());
    }
    @Test void missingOrCorruptCasefoldDataCannotFallBackToWeakerValidation() throws Exception {
        var classes=SourceEvidenceCaseFold.class.getProtectionDomain().getCodeSource().getLocation();
        for(boolean missing:new boolean[]{true,false}) {
            try(var isolated=new java.net.URLClassLoader(new java.net.URL[]{classes},ClassLoader.getPlatformClassLoader()) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    if(name.equals("unicode/CaseFolding-15.0.0.txt")) return missing?null:new java.io.ByteArrayInputStream("corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return super.getResourceAsStream(name);
                }
            }) {
                assertThatThrownBy(()->Class.forName(SourceEvidenceCaseFold.class.getName(),true,isolated))
                        .isInstanceOf(ExceptionInInitializerError.class).hasCauseInstanceOf(IllegalStateException.class);
            }
        }
    }
    @Test void literalEvidenceExclusionUsesFullDefaultUnicodeCasefold() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture=json.readTree(java.nio.file.Path.of("../contracts/fixtures/source-admission-state-v2.json").toFile());
        for(var vector:fixture.get("evidence_casefold_states")) {
            String raw=vector.get("raw").asText();
            if(vector.get("accepted").booleanValue()) {
                assertThat(SourceAdmissionState.decode(raw).raw()).as(vector.get("name").asText()).isEqualTo(raw);
                assertThat(SourceAdmissionState.decode(raw).digest()).isEqualTo(vector.get("sha256").asText());
            } else assertThatThrownBy(()->SourceAdmissionState.decode(raw)).as(vector.get("name").asText()).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void literalContactDisplayMinimumCountsUnicodeCodepoints() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture=json.readTree(java.nio.file.Path.of("../contracts/fixtures/source-admission-state-v2.json").toFile());
        for(var vector:fixture.get("contact_states")) {
            String raw=vector.get("raw").asText();
            if(vector.get("accepted").booleanValue()) {
                assertThat(SourceAdmissionState.decode(raw).digest()).as(vector.get("name").asText()).isEqualTo(vector.get("sha256").asText());
            } else assertThatThrownBy(()->SourceAdmissionState.decode(raw)).as(vector.get("name").asText()).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void literalStateRequestAndTerminalReplyVectors() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture=json.readTree(java.nio.file.Path.of("../contracts/fixtures/source-admission-state-v2.json").toFile());
        for(var vector:fixture.get("states")) {
            var state=SourceAdmissionState.decode(vector.get("raw").asText());
            assertThat(state.raw()).isEqualTo(vector.get("raw").asText());
            assertThat(state.digest()).isEqualTo(vector.get("sha256").asText());
        }
        for(var vector:fixture.get("requests")) assertThat(encodeRequest(decodeRequest(vector.get("raw").asText()))).isEqualTo(vector.get("raw").asText());
        for(var vector:fixture.get("replies")) assertThat(encodeReply(decodeReply(vector.get("raw").asText()))).isEqualTo(vector.get("raw").asText());
        for(var vector:fixture.get("invalid_states")) assertThatThrownBy(()->SourceAdmissionState.decode(vector.get("raw").asText())).isInstanceOf(IllegalArgumentException.class);
        assertThat(encodeReply(new AdmissionReply("TERMINAL","JOB_TERMINAL",null,null,null,"CANCELED"))).isEqualTo(fixture.get("replies").get(0).get("raw").asText());
        String ready=fixture.get("states").get(0).get("raw").asText();
        for(String invalid:java.util.List.of(ready.replace("\"generation\":0","\"generation\":true"),ready.replace("\"version\":2","\"version\":2,\"version\":2"),ready.replace("\"version\":2","\"version\":2,\"extra\":null"),ready.replace("\"generation\":0","\"generation\":9007199254740992")))
            assertThatThrownBy(()->SourceAdmissionState.decode(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void initializerIsAvailableWithoutApiProfileAndMailboxDefaultsOff() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class,org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor::new)
                .withUserConfiguration(SourceAdmissionRepository.class,com.camel_hub.advertisement.job.config.JobConfiguration.class,
                        com.camel_hub.advertisement.arxiv.extraction.SourceExtractionConfiguration.class)
                .withBean(org.springframework.r2dbc.core.DatabaseClient.class,()->org.mockito.Mockito.mock(org.springframework.r2dbc.core.DatabaseClient.class))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class,com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.camel_hub.advertisement.audit.AuditService.class,()->org.mockito.Mockito.mock(com.camel_hub.advertisement.audit.AuditService.class))
                .withBean(com.camel_hub.advertisement.identity.security.SensitiveValueHasher.class,()->org.mockito.Mockito.mock(com.camel_hub.advertisement.identity.security.SensitiveValueHasher.class))
                .withBean(org.springframework.transaction.reactive.TransactionalOperator.class,()->org.mockito.Mockito.mock(org.springframework.transaction.reactive.TransactionalOperator.class))
                .run(context->{
                    assertThat(context).hasSingleBean(SourceAdmissionRepository.class);
                    assertThat(context).hasSingleBean(com.camel_hub.advertisement.job.persistence.JobRepository.class);
                    assertThat(context).doesNotHaveBean(SourceAdmissionMailbox.class);
                });
    }
    @Test void rejectsUnknownFieldsDuplicateFieldsAndNoncanonicalTime() {
        assertThatThrownBy(() -> decodeRequest("{\"operation\":\"BIND\",\"operation\":\"RESERVE\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decodeRequest("{\"operation\":\"BIND\",\"url\":\"blocked\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionReply("RESERVED","OK",null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionReply("RUN","OK",null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void slotsAreBoundedAndDerivedFromCanonicalNonce() {
        assertThat(slotKey(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")))
                .isEqualTo("camel:worker:source-admission:{source-admission}:slot:28");
    }
}
