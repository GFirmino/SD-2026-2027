package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ChunkCrypto {
    public static final int KEY_VERSION = 1;
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_PREFIX = "tpf-v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public ChunkCrypto(byte[] masterKey) {
        if (masterKey.length != 32) throw new IllegalArgumentException("A master.key deve ter 32 bytes.");
        this.masterKey = masterKey.clone();
    }

    // HKDF-Extract e primeiro bloco HKDF-Expand (32 bytes para SHA-256).
    static byte[] deriveKey(byte[] inputKey, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(inputKey);
        try {
            hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
            hmac.update(info);
            return hmac.doFinal(new byte[] {1});
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    private static byte[] aad(int version, byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(AAD_PREFIX.length + 4 + salt.length + nonce.length)
                .put(AAD_PREFIX).putInt(version).put(salt).put(nonce).array();
    }

    public Sealed seal(byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] key = deriveKey(masterKey, salt, INFO);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(KEY_VERSION, salt, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new Sealed(hash(ciphertext), KEY_VERSION, salt, nonce, ciphertext);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public byte[] open(Sealed sealed) throws GeneralSecurityException {
        byte[] ciphertext = sealed.ciphertext();
        // A ordem faz parte do protocolo: integridade do nome, versão, autenticação.
        if (!hash(ciphertext).equals(sealed.id())) throw new GeneralSecurityException("SHA-256 diferente do chunkId.");
        if (sealed.keyVersion() != KEY_VERSION) throw new GeneralSecurityException("Versão de chave desconhecida.");
        byte[] salt = sealed.salt();
        byte[] nonce = sealed.nonce();
        if (salt.length != 32 || nonce.length != 12) throw new GeneralSecurityException("Salt/nonce inválido.");
        byte[] key = deriveKey(masterKey, salt, INFO);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(sealed.keyVersion(), salt, nonce));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static String hash(byte[] ciphertext) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
    }
}
