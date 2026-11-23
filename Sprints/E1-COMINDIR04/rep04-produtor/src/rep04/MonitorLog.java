package rep04;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

public final class MonitorLog {
    private final Path path;

    // Este método serve para escolher o ficheiro de eventos deste participante.
    public MonitorLog(String id) {
        path = Path.of("monitor-" + id + ".txt");
    }

    // Este método serve para escrever um evento com o instante atual, sem misturar escritas das
    // threads.
    public void event(String event) {
        synchronized (MonitorLog.class) {
            String line = System.currentTimeMillis() + ";" + event;

            try {
                Files.writeString(
                        path,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            System.out.println(line);
        }
    }

    // Este método serve para registar os dados do heartbeat e o intervalo de registos recebidos.
    public void received(int k, Batch b) {
        long from =
                b.count == 0
                        ? 0
                        : (b.records.isEmpty()
                                ? b.lastSeq - b.count + 1
                                : b.records.get(0).getSeq());
        long to =
                b.count == 0
                        ? 0
                        : (b.records.isEmpty()
                                ? b.lastSeq
                                : b.records.get(b.records.size() - 1).getSeq());
        event(
            "RECEBIDO;p" + k
            + ";inc=" + b.inc
            + ";hb=" + b.hb
            + ";ultimoSeq=" + b.lastSeq
            + ";n=" + b.count
            + ";de=" + from
            + ";ate="+ to
        );
    }

    // Este método serve para verificar periodicamente todos os monitores numa thread dedicada.
    public static void schedule(HeartbeatMonitor... monitors) {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        (r) -> {
                            Thread t = new Thread(r, "verificacao-heartbeats");

                            t.setDaemon(true);

                            return t;
                        });
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        for (HeartbeatMonitor m : monitors) m.check();
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                },
                Wire.VERIFICACAO_MS,
                Wire.VERIFICACAO_MS,
                TimeUnit.MILLISECONDS);
    }
}
