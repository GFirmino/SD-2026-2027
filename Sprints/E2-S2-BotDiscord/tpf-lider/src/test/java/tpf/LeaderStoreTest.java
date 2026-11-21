package tpf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LeaderStoreTest {
    @TempDir Path directory;

    private List<Path> binaries() throws IOException {
        try (var files = Files.list(directory.resolve("selados"))) {
            return files.filter(p -> p.toString().endsWith(".bin")).toList();
        }
    }

    private List<Registo> openAll() throws Exception {
        ChunkCrypto crypto = new ChunkCrypto(Files.readAllBytes(directory.resolve("master.key")));
        List<Registo> records = new ArrayList<>();
        for (Path bin : binaries()) {
            String name = bin.getFileName().toString();
            records.addAll(Chunk.read(crypto.open(Sealed.load(bin.getParent(), name.substring(0, 64)))));
        }
        records.sort(Comparator.comparingLong(Registo::lsn));
        return records;
    }

    @Test void durableLineExistsBeforeReturningAndLsnSurvivesRestart() throws Exception {
        String text = "Olá; @everyone\nsegunda linha\r\n😀";
        try (LeaderStore store = new LeaderStore(directory)) {
            assertEquals(1, store.registar("123", "456", text));
            Registo first = Registo.fromLine(Files.readAllLines(directory.resolve("registos.log")).get(0));
            assertEquals(text, first.texto());
            assertEquals(2, store.registar("123", "456", ""));
        }
        List<Path> before = binaries();
        try (LeaderStore store = new LeaderStore(directory)) {
            assertEquals(3, store.registar("789", "456", "fim"));
        }
        assertTrue(binaries().containsAll(before));
        assertEquals(List.of(1L, 2L, 3L), openAll().stream().map(Registo::lsn).toList());
    }

    @Test @Timeout(15) void oneRecordSealsAfterDefaultFiveSecondsWithoutClose() throws Exception {
        try (LeaderStore store = new LeaderStore(directory)) {
            long start = System.nanoTime(); store.registar("123", "456", "um registo");
            while (binaries().isEmpty() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10)) Thread.sleep(20);
            assertEquals(1, binaries().size());
            assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(4500));
            assertEquals(1, openAll().size());
        }
    }

    @Test void fiveHundredRecordsSealSeveralChunksBySize() throws Exception {
        try (LeaderStore store = new LeaderStore(directory, 65536, 4096, 60000)) {
            for (int i = 0; i < 500; i++) store.registar("123", "456", "Rajada " + i + " | " + "0123456789abcdef".repeat(16));
            assertTrue(binaries().size() >= 2, "selagem por tamanho antes do prazo");
        }
        assertEquals(500, openAll().size());
        for (Path binary : binaries()) {
            assertEquals(0, (Files.size(binary) - 16) % 4096);
            assertEquals(binary.getFileName().toString().substring(0, 64), ChunkCrypto.hash(Files.readAllBytes(binary)));
        }
        List<String> events = Files.readAllLines(directory.resolve("eventos-lider.txt"));
        assertEquals(500, events.stream().filter(s -> s.contains(";REGISTO;")).count());
        ChunkCrypto crypto = new ChunkCrypto(Files.readAllBytes(directory.resolve("master.key")));
        for (String event : events) if (event.contains(";SELADO;")) {
            Map<String, String> fields = new HashMap<>();
            for (String field : event.split(";")) if (field.contains("=")) {
                String[] pair = field.split("=", 2); fields.put(pair[0], pair[1]);
            }
            List<Registo> records = Chunk.read(crypto.open(Sealed.load(directory.resolve("selados"), fields.get("chunkId"))));
            assertEquals(Long.parseLong(fields.get("de")), records.get(0).lsn());
            assertEquals(Long.parseLong(fields.get("ate")), records.get(records.size() - 1).lsn());
        }
    }

    @Test void concurrentWritersGetExactlyOneContiguousSequence() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (LeaderStore store = new LeaderStore(directory)) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                int item = i; results.add(pool.submit(() -> store.registar("123", "456", "concorrente " + item)));
            }
            Set<Long> lsns = new TreeSet<>();
            for (Future<Long> result : results) lsns.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(120, lsns.size()); assertTrue(lsns.containsAll(List.of(1L, 120L)));
            assertEquals(120, Files.readAllLines(directory.resolve("registos.log")).size());
        } finally { pool.shutdownNow(); }
        assertEquals(120, openAll().size());
    }

    @Test void recordThatExceedsLimitBelongsToTheChunkSealedImmediately() throws Exception {
        try (LeaderStore store = new LeaderStore(directory, 70, 64, 60000)) {
            store.registar("123", "456", "x"); assertTrue(binaries().isEmpty());
            store.registar("123", "456", "y"); assertEquals(1, binaries().size());
            assertEquals(List.of(1L, 2L), openAll().stream().map(Registo::lsn).toList());
            store.registar("123", "456", "z"); assertEquals(1, binaries().size());
        }
        assertEquals(List.of(1L, 2L, 3L), openAll().stream().map(Registo::lsn).toList());
    }

    @Test void invalidMasterKeyAndConcurrentLeaderAreRejected() throws Exception {
        try (LeaderStore store = new LeaderStore(directory)) {
            assertThrows(IOException.class, () -> new LeaderStore(directory));
            assertEquals(1, store.registar("123", "456", "continua utilizável"));
            Path key = directory.resolve("master.key"); assertEquals(32, Files.size(key));
            AclFileAttributeView acl = Files.getFileAttributeView(key, AclFileAttributeView.class);
            if (acl != null) {
                assertEquals(1, acl.getAcl().size());
                assertEquals(Files.getOwner(key), acl.getAcl().get(0).principal());
                assertTrue(acl.getAcl().get(0).permissions().contains(AclEntryPermission.READ_DATA));
            } else assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(key));
        }
        Path other = directory.resolve("invalida"); Files.createDirectories(other);
        Files.write(other.resolve("master.key"), new byte[31]);
        assertThrows(IOException.class, () -> new LeaderStore(other));
        assertEquals(31, Files.size(other.resolve("master.key")));
    }

    @Test void incompleteTailIsDiscardedButMiddleCorruptionIsNotIgnored() throws Exception {
        try (LeaderStore store = new LeaderStore(directory)) { store.registar("123", "456", "válido"); }
        Files.writeString(directory.resolve("registos.log"), "2;incompleto", StandardOpenOption.APPEND);
        try (LeaderStore store = new LeaderStore(directory)) { assertEquals(2, store.registar("123", "456", "seguinte")); }
        Files.writeString(directory.resolve("registos.log"), "linha inválida\n", StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> new LeaderStore(directory));
    }

    @Test void restartAfterAbruptHaltReplaysDurableButUnsealedRecords() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(java, "-cp", classpath, "tpf.CrashWriter", directory.toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("crash-writer.txt").toFile()).start();
        assertTrue(child.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
        assertEquals(5, Files.readAllLines(directory.resolve("registos.log")).size());
        assertTrue(binaries().isEmpty());
        try (LeaderStore store = new LeaderStore(directory)) { assertEquals(6, store.registar("123", "456", "após reinício")); }
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), openAll().stream().map(Registo::lsn).toList());
    }
}
