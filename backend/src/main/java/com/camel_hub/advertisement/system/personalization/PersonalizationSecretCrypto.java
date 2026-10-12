package com.camel_hub.advertisement.system.personalization;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
final class PersonalizationSecretCrypto {
    private final SecretKeySpec key;
    private static final byte[] AAD = "camel-arxiv:personalization-api-key:v1".getBytes(StandardCharsets.UTF_8);
    PersonalizationSecretCrypto(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES"); Arrays.fill(bytes, (byte) 0);
        } catch (Exception e) { throw new IllegalStateException("Personalization encryption key configuration is invalid"); }
    }
    String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce)); cipher.updateAAD(AAD);
            byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext;
            try { ciphertext = cipher.doFinal(plaintext); } finally { Arrays.fill(plaintext, (byte) 0); }
            byte[] result = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) { throw new IllegalStateException("Personalization secret is unavailable"); }
    }
    String decrypt(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(bytes, 12))); cipher.updateAAD(AAD);
            byte[] plaintext = cipher.doFinal(bytes, 12, bytes.length - 12);
            try { return new String(plaintext, StandardCharsets.UTF_8); } finally { Arrays.fill(plaintext, (byte) 0); }
        } catch (Exception e) { throw new IllegalStateException("Personalization secret is unavailable"); }
    }
}
