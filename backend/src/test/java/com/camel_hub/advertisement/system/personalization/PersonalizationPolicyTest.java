package com.camel_hub.advertisement.system.personalization;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class PersonalizationPolicyTest {
    @Test void normalizesRootsAndRejectsUnsafeOrigins() {
        assertThat(PersonalizationPolicy.origin("https://API.example.com/")).isEqualTo("https://api.example.com/v1");
        assertThat(PersonalizationPolicy.origin("https://api.example.com/proxy/v2///")).isEqualTo("https://api.example.com/proxy/v2");
        for (String value : new String[]{"http://example.com", "https://localhost", "https://127.0.0.1", "https://user:secret@example.com", "https://example.com?q=secret", "https://example.com/#a", "https://[::1]"})
            assertThatThrownBy(() -> PersonalizationPolicy.origin(value)).hasMessage("API origin must be a public HTTPS endpoint");
    }
    @Test void acceptsPublicIpv6OriginsAndNormalizesDefaultHttpsPort() {
        assertThat(PersonalizationPolicy.origin("https://[2606:4700:4700::1111]/v1"))
                .isEqualTo("https://[2606:4700:4700:0:0:0:0:1111]/v1");
        assertThat(PersonalizationPolicy.origin("https://[2606:4700:4700::1111]:443/"))
                .isEqualTo("https://[2606:4700:4700:0:0:0:0:1111]/v1");
        assertThat(PersonalizationPolicy.origin("https://[2606:4700:4700::ABCD]:8443/proxy/v2/"))
                .isEqualTo("https://[2606:4700:4700:0:0:0:0:abcd]:8443/proxy/v2");
        assertThat(PersonalizationPolicy.origin("https://[2606:4700:4700:0000:0000:0000:0000:1111]/v1"))
                .isEqualTo("https://[2606:4700:4700:0:0:0:0:1111]/v1");
    }
    @Test void rejectsUnsafeIpv6OriginsAndSingleLabelDnsHosts() {
        for (String host : new String[]{"[::1]", "[fc00::1]", "[fe80::1]", "[2001:db8::1]", "[3fff::1]", "[::ffff:127.0.0.1]", "internal"})
            assertThatThrownBy(() -> PersonalizationPolicy.origin("https://" + host + "/v1"))
                    .as(host).hasMessage("API origin must be a public HTTPS endpoint");
    }
    @Test void rejectsPrivateReservedAndMappedAddressesAtConnectionResolution() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "192.0.2.1", "198.18.0.1", "192.88.99.1", "224.0.0.1", "::1", "fc00::1", "2001:db8::1", "3fff::1", "::ffff:127.0.0.1"})
            assertThat(PersonalizationPolicy.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(PersonalizationPolicy.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
    @Test void encryptsWithFreshNoncesAndRejectsTampering() {
        var crypto = new PersonalizationSecretCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        String a = crypto.encrypt("secret-key"), b = crypto.encrypt("secret-key");
        assertThat(a).isNotEqualTo(b).doesNotContain("secret-key");
        assertThat(crypto.decrypt(a)).isEqualTo("secret-key");
        byte[] bytes = Base64.getDecoder().decode(a); bytes[15] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(Base64.getEncoder().encodeToString(bytes))).hasMessage("Personalization secret is unavailable");
    }
}
