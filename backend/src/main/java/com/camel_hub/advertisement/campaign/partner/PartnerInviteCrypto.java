package com.camel_hub.advertisement.campaign.partner;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Dedicated AES-256-GCM capability encryption, bound to the contact HMAC. */
public final class PartnerInviteCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public PartnerInviteCrypto(String keyBase64) {
        try {
            this.key = keyBase64 == null || keyBase64.isBlank() ? new byte[0]
                    : Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Partner invite encryption key is invalid");
        }
        if (key.length != 0 && key.length != 32) {
            throw new IllegalArgumentException("Partner invite encryption key must contain 32 bytes");
        }
    }

    public byte[] encrypt(byte[] emailHmac, String claimUrl) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(emailHmac);
            byte[] encrypted = cipher.doFinal(claimUrl.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception error) {
            throw new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE);
        }
    }

    public String decrypt(byte[] emailHmac, byte[] encryptedUrl) {
        requireKey();
        if (encryptedUrl == null || encryptedUrl.length < 29 || encryptedUrl.length > 4096) {
            throw new PartnerInviteException(PartnerInviteException.Reason.HELD);
        }
        try {
            byte[] nonce = Arrays.copyOf(encryptedUrl, 12);
            byte[] ciphertext = Arrays.copyOfRange(encryptedUrl, 12, encryptedUrl.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(emailHmac);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new PartnerInviteException(PartnerInviteException.Reason.HELD);
        }
    }

    public boolean configured() { return key.length == 32; }

    private void requireKey() {
        if (!configured()) throw new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE);
    }

    @Override public String toString() { return "PartnerInviteCrypto[redacted]"; }
}
