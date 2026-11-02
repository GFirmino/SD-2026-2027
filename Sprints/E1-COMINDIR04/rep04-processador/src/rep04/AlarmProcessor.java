package rep04;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AlarmProcessor {
    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final List<Set<Long>> seen = new ArrayList<>();
    private final MonitorLog log = new MonitorLog("processador");
    private final HeartbeatMonitor[] monitors;
    private long lamport;
    private final VectorClock vector;

    // Este método serve para criar relógios, monitores e conjuntos de registos já observados.
    public AlarmProcessor(int n, String api) {
        partitioner = new Partitioner(n);
        pubSub = new IpfsPubSub(api);
        vector = new VectorClock(n);
        monitors = new HeartbeatMonitor[n];

        for (int k = 0; k < n; k++)
            monitors[k] =
                    new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
        MonitorLog.schedule(monitors);

        for (int k = 0; k < n; k++) seen.add(new HashSet<>());
    }

    // Este método serve para validar um lote, atualizar o monitor e tratar os seus registos.
    private void onMessage(int k, String msg) {
        try {
            Batch batch = Batch.decode(msg, partitioner.count());

            for (SensorRecord r : batch.records)
                if (partitioner.of(r.getSensorId()) != k)
                    throw new IllegalArgumentException("Particao errada");
            monitors[k].onHeartbeat(batch.inc, batch.hb);
            log.received(k, batch);

            for (SensorRecord r : batch.records) onRecord(k, r);
        } catch (IllegalArgumentException e) {
            System.err.println("LOTE REJEITADO: " + e.getMessage());
        }
    }

    // Este método serve para acumular conhecimento causal e publicar um alarme quando necessário.
    private void onRecord(int k, SensorRecord record) {
        if (record.getSensorId().startsWith("ALARM-")) return;
        long snapshotL;
        VectorClock snapshotV;

        synchronized (this) {
            if (!seen.get(k).add(record.getSeq())) return;
            lamport = Math.max(lamport, record.getLamport());
            vector.merge(record.getVector());
            snapshotL = lamport;
            snapshotV = vector.copy();
        }

        if (record.getTemperature() < Wire.LIMIAR_ALARME) return;
        String sensor = "ALARM-" + record.getSensorId();
        int target = partitioner.of(sensor);

        if (monitors[target].state() == HeartbeatMonitor.State.SUSPEITO)
            System.out.println(
                    "AVISO: lider p" + target + " suspeito; alarme " + sensor + " pode perder-se");

        try {
            pubSub.publish(
                    Wire.topicIn(target),
                    Wire.in(sensor, record.getTemperature(), snapshotL, snapshotV));
            System.out.println(
                    "ALARME: "
                            + record.getSensorId()
                            + " p"
                            + k
                            + "#"
                            + record.getSeq()
                            + " → p"
                            + target
                            + " L="
                            + snapshotL
                            + " V="
                            + snapshotV);
        } catch (IOException e) {
            System.err.println(
                    "ALARME NÃO PUBLICADO: p" + k + "#" + record.getSeq() + " " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Este método serve para subscrever os tópicos de dados de todas as partições.
    public void start() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int k = 0; k < partitioner.count(); k++) {
            final int part = k;

            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    pubSub.subscribe(
                                            Wire.topicData(part), (msg) -> onMessage(part, msg));
                                } catch (Exception e) {
                                    System.err.println(
                                            "SUBSCRIÇÃO FALHOU [p" + part + "]: " + e.getMessage());
                                    System.exit(1);
                                }
                            },
                            "processador-p" + k);
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) thread.join();
    }

    // Este método serve para ler a configuração e arrancar o processador de alarmes.
    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2)
                throw new IllegalArgumentException("Uso: AlarmProcessor <N> [api]");
            String api = args.length == 2 ? args[1] : "http://127.0.0.1:5001/api/v0";

            new AlarmProcessor(Integer.parseInt(args[0]), api).start();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
