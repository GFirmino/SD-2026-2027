package tpf;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;

/** Caminho de escrita único do líder: WAL durável, chunk aberto e selagem. */
public final class LeaderStore implements AutoCloseable {
    private final Path directory;
    private final int maxBytes, padBytes;
    private final long sealingMs;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "selagem-chunk");
        thread.setDaemon(true);
        return thread;
    });
    private FileChannel journal, lockChannel;
    private FileLock lock;
    private ChunkCrypto crypto;
    private Chunk open = new Chunk();
    private ScheduledFuture<?> deadline;
    private long lastLsn;
    private IOException failure;
    private boolean closed;

    public LeaderStore(Path directory) throws IOException {
        this(directory, Wire.CHUNK_MAX_BYTES, Wire.PAD_BYTES, Wire.SELAGEM_MS);
    }

    LeaderStore(Path directory, int maxBytes, int padBytes, long sealingMs) throws IOException {
        if (maxBytes < 1 || padBytes < 1 || sealingMs < 1) throw new IllegalArgumentException("Limites inválidos.");
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.padBytes = padBytes;
        this.sealingMs = sealingMs;
        try {
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve("lider.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException exception) { throw new IOException("Já existe um líder nesta pasta.", exception); }
            if (lock == null) throw new IOException("Já existe um líder nesta pasta.");
            journal = FileChannel.open(directory.resolve("registos.log"), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            List<Registo> records = readJournal();
            Path sealedDirectory = directory.resolve("selados");
            Files.createDirectories(sealedDirectory);
            List<Path> binaries;
            try (var files = Files.list(sealedDirectory)) {
                binaries = files.filter(p -> p.getFileName().toString().endsWith(".bin")).sorted().toList();
            }
            Path keyPath = directory.resolve("master.key");
            if (!Files.exists(keyPath) && !binaries.isEmpty()) throw new IOException("Falta a master.key dos chunks existentes.");
            crypto = new ChunkCrypto(MasterKey.loadOrCreate(keyPath));
            Set<Long> sealedLsns = new HashSet<>();
            for (Path binary : binaries) {
                String name = binary.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                List<Registo> contents = Chunk.read(crypto.open(Sealed.load(sealedDirectory, id)));
                for (Registo record : contents) {
                    if (record.lsn() > records.size() || !records.get(Math.toIntExact(record.lsn() - 1)).equals(record)
                            || !sealedLsns.add(record.lsn())) throw new IOException("Chunk divergente ou sobreposto ao registo durável.");
                }
            }
            // Repor apenas a cauda local que ainda não chegou a um .bin completo.
            synchronized (this) {
                for (Registo record : records) {
                    if (!sealedLsns.contains(record.lsn())) {
                        open.add(record);
                        if (open.size() >= maxBytes) sealOpen();
                    }
                }
                if (!open.isEmpty()) schedule(Math.max(0, sealingMs - (System.currentTimeMillis() - open.firstInstant())));
            }
        } catch (GeneralSecurityException | RuntimeException exception) {
            release();
            throw new IOException("Não foi possível abrir o armazenamento do líder.", exception);
        } catch (IOException exception) {
            release();
            throw exception;
        }
    }

    private List<Registo> readJournal() throws IOException {
        // Um fim sem newline não chegou ao force/ack completo: não o tratar como registo válido.
        long length = journal.size();
        if (length > 0) {
            ByteBuffer one = ByteBuffer.allocate(1);
            long end = length;
            while (end > 0) {
                one.clear(); journal.read(one, end - 1);
                if (one.array()[0] == '\n') break;
                end--;
            }
            if (end != length) { journal.truncate(end); journal.force(true); }
        }
        List<Registo> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve("registos.log"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Registo record;
                try { record = Registo.fromLine(line); }
                catch (IllegalArgumentException exception) { throw new IOException("Linha inválida em registos.log.", exception); }
                if (record.lsn() != records.size() + 1L) throw new IOException("LSN repetido ou em falta em registos.log.");
                records.add(record);
            }
        }
        lastLsn = records.size();
        journal.position(journal.size());
        return records;
    }

    public synchronized long registar(String autor, String canal, String texto) throws IOException {
        ensureWritable();
        Registo record = new Registo(Math.addExact(lastLsn, 1), System.currentTimeMillis(), autor, canal, texto);
        record.binarySize();
        try {
            ByteBuffer line = ByteBuffer.wrap((record.toLine() + "\n").getBytes(StandardCharsets.UTF_8));
            while (line.hasRemaining()) journal.write(line);
            journal.force(true); // Nunca confirmar um LSN antes desta operação.
            lastLsn = record.lsn();
            event("REGISTO", "lsn=" + lastLsn);
            boolean first = open.isEmpty();
            open.add(record);
            if (open.size() >= maxBytes) sealOpen();
            else if (first) schedule(sealingMs);
            return lastLsn;
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }
    }

    private void schedule(long delay) {
        Chunk expected = open;
        deadline = timer.schedule(() -> {
            synchronized (LeaderStore.this) {
                if (closed || failure != null || open != expected || open.isEmpty()) return;
                try { sealOpen(); }
                catch (IOException exception) {
                    failure = exception;
                    System.err.println("Falha na selagem: " + exception.getMessage());
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sealOpen() throws IOException {
        if (open.isEmpty()) return;
        try {
            byte[] padded = open.seal(padBytes);
            Sealed sealed = crypto.seal(padded);
            sealed.save(directory.resolve("selados"));
            event("SELADO", "chunkId=" + sealed.id() + ";de=" + open.firstLsn() + ";ate=" + open.lastLsn()
                    + ";tamanho=" + sealed.size() + ";paddingBytes=" + padded.length);
            if (deadline != null) deadline.cancel(false);
            open = new Chunk();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Falha a cifrar o chunk.", exception);
        }
    }

    private void event(String name, String details) throws IOException {
        Files.writeString(directory.resolve("eventos-lider.txt"), System.currentTimeMillis() + ";" + name + ";" + details + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void ensureWritable() throws IOException {
        if (closed) throw new IOException("O armazenamento está fechado.");
        if (failure != null) throw new IOException("Armazenamento indisponível após uma falha de escrita/selagem.", failure);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        try {
            if (failure == null) sealOpen();
        } finally {
            closed = true;
            release();
        }
    }

    private void release() {
        timer.shutdownNow();
        for (AutoCloseable resource : new AutoCloseable[] {journal, lock, lockChannel}) {
            if (resource != null) {
                try { resource.close(); }
                catch (Exception exception) { System.err.println("Falha a fechar o armazenamento: " + exception.getClass().getSimpleName()); }
            }
        }
    }
}
