package tpf;

import java.nio.file.Path;

public final class Wire {
    public static final int CHUNK_MAX_BYTES = Integer.getInteger("tpf.chunkMaxBytes", 65536);
    public static final int PAD_BYTES = Integer.getInteger("tpf.padBytes", 4096);
    public static final long SELAGEM_MS = Long.getLong("tpf.selagemMs", 5000);
    public static final Path DATA_DIR = Path.of(System.getProperty("tpf.dataDir", "dados-lider"));

    static {
        if (CHUNK_MAX_BYTES < 1 || PAD_BYTES < 1 || SELAGEM_MS < 1) {
            throw new IllegalArgumentException("Os limites de chunk, padding e selagem devem ser positivos.");
        }
    }

    private Wire() { }
}
