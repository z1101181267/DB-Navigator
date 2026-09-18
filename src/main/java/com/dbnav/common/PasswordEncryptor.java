package com.dbnav.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password encryption utility (AES-256-GCM).
 *
 * Database passwords are encrypted at rest, so a leaked database file does not
 * directly expose credentials. AES-GCM with a fresh random IV per record — the
 * same plaintext therefore produces different ciphertext every time.
 */
public class PasswordEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    // In production, load from environment variable or secret manager.
    // The passphrase is hashed with SHA-256 so the derived key is ALWAYS
    // exactly 32 bytes, regardless of passphrase length.
    private static final byte[] SECRET_KEY = deriveKey();

    private static byte[] deriveKey() {
        String passphrase = System.getenv("DBNAV_SECRET_KEY");
        if (passphrase == null || passphrase.isBlank()) {
            // Default development passphrase - MUST be overridden in production
            passphrase = "dbnav-default-dev-passphrase-change-me";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive encryption key", e);
        }
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(SECRET_KEY, ALGORITHM);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, combined, IV_LENGTH, encrypted.length);

            return "enc:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Password encryption failed", e);
        }
    }

    public static String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty() || !ciphertext.startsWith("enc:")) {
            return ciphertext != null ? ciphertext : "";
        }
        try {
            String encoded = ciphertext.substring(4);
            byte[] combined = Base64.getDecoder().decode(encoded);

            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(SECRET_KEY, ALGORITHM);
            GCMParameterSpec paramSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Password decryption failed", e);
        }
    }
}
