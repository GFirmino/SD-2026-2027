package tpf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChunkCryptoTest {
    @TempDir Path directory;

    private byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test void hkdfMatchesFirstBlockOfRfc5869Vector() throws Exception {
        byte[] input = new byte[22]; Arrays.fill(input, (byte) 0x0b);
        byte[] salt = HexFormat.of().parseHex("000102030405060708090a0b0c");
        byte[] info = HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
                HexFormat.of().formatHex(ChunkCrypto.deriveKey(input, salt, info)));
    }

    @Test void roundTripAndDiskFormatPreserveAllRecords() throws Exception {
        Registo record = new Registo(1, 10, "autor", "canal", "Olá; texto\ncom acentos e emoji 😀");
        Chunk chunk = new Chunk(); chunk.add(record);
        byte[] padded = chunk.seal(4096);
        ChunkCrypto crypto = new ChunkCrypto(key());
        Sealed sealed = crypto.seal(padded);
        sealed.save(directory);
        Sealed loaded = Sealed.load(directory, sealed.id());
        assertArrayEquals(padded, crypto.open(loaded));
        assertEquals(List.of(record), Chunk.read(crypto.open(loaded)));
        assertEquals(0, padded.length % 4096);
        assertEquals(padded.length + 16, Files.size(directory.resolve(sealed.id() + ".bin")));
        assertEquals(sealed.id(), ChunkCrypto.hash(Files.readAllBytes(directory.resolve(sealed.id() + ".bin"))));
        assertFalse(new String(loaded.ciphertext(), StandardCharsets.UTF_8).contains(record.texto()));
        assertThrows(IllegalStateException.class, () -> chunk.add(record));
    }

    @Test void freshSaltNonceAndDefensiveCopies() throws Exception {
        ChunkCrypto crypto = new ChunkCrypto(key());
        byte[] text = new byte[4096];
        Sealed first = crypto.seal(text), second = crypto.seal(text);
        assertFalse(Arrays.equals(first.salt(), second.salt()));
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertNotEquals(first.id(), second.id());
        first.salt()[0] ^= 1; first.nonce()[0] ^= 1; first.ciphertext()[0] ^= 1;
        assertArrayEquals(text, crypto.open(first));
    }

    @Test void changedCiphertextFailsHashBeforeVersion() throws Exception {
        ChunkCrypto crypto = new ChunkCrypto(key()); Sealed sealed = crypto.seal(new byte[4096]);
        byte[] changed = sealed.ciphertext(); changed[0] ^= 1;
        Sealed bad = new Sealed(sealed.id(), 2, sealed.salt(), sealed.nonce(), changed);
        GeneralSecurityException error = assertThrows(GeneralSecurityException.class, () -> crypto.open(bad));
        assertTrue(error.getMessage().contains("SHA-256"));
        Sealed renamed = new Sealed(ChunkCrypto.hash(changed), 1, sealed.salt(), sealed.nonce(), changed);
        assertThrows(AEADBadTagException.class, () -> crypto.open(renamed));
    }

    @Test void changedSaltNonceVersionOrMasterKeyFail() throws Exception {
        byte[] master = key(); ChunkCrypto crypto = new ChunkCrypto(master);
        Sealed sealed = crypto.seal(new byte[4096]);
        byte[] salt = sealed.salt(); salt[0] ^= 1;
        byte[] nonce = sealed.nonce(); nonce[0] ^= 1;
        assertThrows(AEADBadTagException.class, () -> crypto.open(new Sealed(sealed.id(), 1, salt, sealed.nonce(), sealed.ciphertext())));
        assertThrows(AEADBadTagException.class, () -> crypto.open(new Sealed(sealed.id(), 1, sealed.salt(), nonce, sealed.ciphertext())));
        GeneralSecurityException version = assertThrows(GeneralSecurityException.class,
                () -> crypto.open(new Sealed(sealed.id(), 2, salt, nonce, sealed.ciphertext())));
        assertTrue(version.getMessage().contains("Versão"));
        master[0] ^= 1;
        assertThrows(AEADBadTagException.class, () -> new ChunkCrypto(master).open(sealed));
    }

    @Test void malformedChunkAndHeaderAreRejected() throws Exception {
        assertThrows(java.io.IOException.class, () -> Chunk.read(new byte[12]));
        String id = "a".repeat(64);
        Files.writeString(directory.resolve(id + ".hdr"), "1;%%%invalid;nonce");
        assertThrows(java.io.IOException.class, () -> Sealed.load(directory, id));
        assertThrows(java.io.IOException.class, () -> Sealed.load(directory, "../master.key"));
    }

    @Test void editingPersistedBinaryAndHeaderMakesOpenFail() throws Exception {
        ChunkCrypto crypto = new ChunkCrypto(key());
        Sealed sealed = crypto.seal(new byte[4096]); sealed.save(directory);
        Path bin = directory.resolve(sealed.id() + ".bin"), hdr = directory.resolve(sealed.id() + ".hdr");
        byte[] changed = Files.readAllBytes(bin); changed[10] ^= 1; Files.write(bin, changed);
        assertThrows(GeneralSecurityException.class, () -> crypto.open(Sealed.load(directory, sealed.id())));
        Files.write(bin, sealed.ciphertext());
        byte[] salt = sealed.salt(); salt[0] ^= 1;
        Files.writeString(hdr, "1;" + Base64.getEncoder().encodeToString(salt) + ";"
                + Base64.getEncoder().encodeToString(sealed.nonce()) + "\n");
        assertThrows(AEADBadTagException.class, () -> crypto.open(Sealed.load(directory, sealed.id())));
    }
}
