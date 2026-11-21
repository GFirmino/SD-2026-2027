package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;

/** O conteúdo e os metadados são copiados para não mudar depois da cifra. */
public final class Sealed {
    private final String id;
    private final int keyVersion;
    private final byte[] salt, nonce, ciphertext;

    public Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("chunkId inválido.");
        this.id = id;
        this.keyVersion = keyVersion;
        this.salt = salt.clone();
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }

    public String id() { return id; }
    public int keyVersion() { return keyVersion; }
    public byte[] salt() { return salt.clone(); }
    public byte[] nonce() { return nonce.clone(); }
    public byte[] ciphertext() { return ciphertext.clone(); }
    public int size() { return ciphertext.length; }

    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        String header = keyVersion + ";" + Base64.getEncoder().encodeToString(salt) + ";"
                + Base64.getEncoder().encodeToString(nonce) + "\n";
        Path headerTemp = directory.resolve(id + ".hdr.tmp");
        Path binaryTemp = directory.resolve(id + ".bin.tmp");
        writeForced(headerTemp, header.getBytes(StandardCharsets.UTF_8));
        writeForced(binaryTemp, ciphertext);
        // O .bin só fica visível com o seu cabeçalho já no destino.
        Files.move(headerTemp, directory.resolve(id + ".hdr"), StandardCopyOption.ATOMIC_MOVE);
        Files.move(binaryTemp, directory.resolve(id + ".bin"), StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    public static Sealed load(Path directory, String id) throws IOException {
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("chunkId inválido.");
        String[] fields = Files.readString(directory.resolve(id + ".hdr"), StandardCharsets.UTF_8).strip().split(";", -1);
        if (fields.length != 3) throw new IOException("Cabeçalho .hdr inválido.");
        try {
            return new Sealed(id, Integer.parseInt(fields[0]), Base64.getDecoder().decode(fields[1]),
                    Base64.getDecoder().decode(fields[2]), Files.readAllBytes(directory.resolve(id + ".bin")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Cabeçalho .hdr inválido.", exception);
        }
    }
}
