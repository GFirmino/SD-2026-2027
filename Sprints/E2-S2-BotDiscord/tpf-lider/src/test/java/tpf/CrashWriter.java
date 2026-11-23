package tpf;

import java.nio.file.Path;

public final class CrashWriter {
    public static void main(String[] args) throws Exception {
        LeaderStore store = new LeaderStore(Path.of(args[0]), 65536, 4096, 60000);
        for (int i = 0; i < 5; i++) store.registar("123", "456", "antes da falha " + i);
        Runtime.getRuntime().halt(0); // Sem close, shutdown hooks ou selagem da cauda.
    }
}
