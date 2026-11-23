package rep04;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class RecordFile {
    private final Path path;
    private final int n;

    // Este método serve para abrir o histórico com a dimensão de vetor por omissão.
    public RecordFile(String name) {
        this(name, Wire.DEFAULT_N);
    }

    // Este método serve para associar um ficheiro de registos à dimensão dos vetores.
    public RecordFile(String name, int n) {
        path = Paths.get(name);
        this.n = n;
    }

    // Este método serve para acrescentar um registo ao ficheiro sem sobrepor os anteriores.
    public synchronized void append(SensorRecord r) throws IOException {
        Files.writeString(
                path,
                r.toLine() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    // Este método serve para ler o último registo e detetar linhas inválidas no histórico.
    public synchronized SensorRecord last() throws IOException {
        if (!Files.exists(path)) return null;
        SensorRecord last = null;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8); ) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) last = SensorRecord.fromLine(line, n);
            }
        }

        return last;
    }

    // Este método serve para obter a última sequência persistida ou zero num histórico vazio.
    public synchronized long lastSeq() throws IOException {
        SensorRecord last = last();

        return last == null ? 0 : last.getSeq();
    }

    // Este método serve para ler os registos dentro do intervalo de sequências pedido.
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();

        if (!Files.exists(path)) return out;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8); ) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                SensorRecord r = SensorRecord.fromLine(line, n);

                if (r.getSeq() >= from && r.getSeq() <= to) out.add(r);
            }
        }

        return out;
    }
}
