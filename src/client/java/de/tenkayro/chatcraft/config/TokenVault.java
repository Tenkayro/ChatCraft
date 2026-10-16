package de.tenkayro.chatcraft.config;

import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

final class TokenVault {
    private static final String PREFIX = "v1:";
    private static final Path KEY_PATH = FabricLoader.getInstance().getConfigDir().resolve(".chatcraft.key");
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenVault() { }

    static synchronized String encrypt(String plaintext) throws IOException, GeneralSecurityException {
        if (plaintext == null || plaintext.isBlank()) return "";
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] payload = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, payload, 0, nonce.length);
        System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    static synchronized String decrypt(String encoded) throws IOException, GeneralSecurityException {
        if (encoded == null || encoded.isBlank()) return "";
        if (!encoded.startsWith(PREFIX)) throw new GeneralSecurityException("Unsupported token format");
        byte[] payload = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
        if (payload.length < 29) throw new GeneralSecurityException("Invalid encrypted token");
        byte[] nonce = java.util.Arrays.copyOfRange(payload, 0, 12);
        byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadOrCreateKey(), "AES"),
                new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] loadOrCreateKey() throws IOException {
        if (Files.isRegularFile(KEY_PATH)) {
            byte[] key = Files.readAllBytes(KEY_PATH);
            if (key.length != 32) throw new IOException("Invalid ChatCraft token key");
            return key;
        }
        Files.createDirectories(KEY_PATH.getParent());
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            Files.write(KEY_PATH, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            return Files.readAllBytes(KEY_PATH);
        }
        restrictKeyFile();
        return key;
    }

    private static void restrictKeyFile() {
        try {
            Files.setPosixFilePermissions(KEY_PATH, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            try {
                Files.setAttribute(KEY_PATH, "dos:hidden", true);
            } catch (UnsupportedOperationException | IOException ignoredAgain) { }
        }
    }
}
