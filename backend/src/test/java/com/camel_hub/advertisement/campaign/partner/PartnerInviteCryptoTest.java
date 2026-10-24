package com.camel_hub.advertisement.campaign.partner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartnerInviteCryptoTest {
    private static final String URL = "https://api.camel-hub.cn/partner/claim?token=secret-token";
    private final PartnerInviteCrypto crypto = new PartnerInviteCrypto(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithFreshNonceAndBindsCiphertextToContact() {
        byte[] contact = new byte[32];
        byte[] first = crypto.encrypt(contact, URL);
        byte[] second = crypto.encrypt(contact, URL);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("secret-token");
        assertThat(crypto.decrypt(contact, first)).isEqualTo(URL);
        contact[0] = 1;
        assertThatThrownBy(() -> crypto.decrypt(contact, first))
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("HELD");
    }

    @Test
    void tamperedCiphertextCannotRevealCapability() {
        byte[] contact = new byte[32];
        byte[] encrypted = crypto.encrypt(contact, URL);
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(contact, encrypted))
                .isInstanceOf(PartnerInviteException.class).hasMessageContaining("HELD")
                .hasMessageNotContaining("secret-token");
    }
}
