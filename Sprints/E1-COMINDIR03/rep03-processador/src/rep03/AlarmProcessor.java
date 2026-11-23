package rep03;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Transporta conhecimento dos líderes; não incrementa nenhum relógio. */
public final class AlarmProcessor {
    private final Partitioner partitioner;
    private final IpfsPubSub pubSub;
    private final List<Set<Long>> seen = new ArrayList<>();
    private long lamport;
    private final VectorClock vector;

    public AlarmProcessor(int n, String api) {
        partitioner = new Partitioner(n);
        pubSub = new IpfsPubSub(api);
        vector = new VectorClock(n);
        for (int k = 0; k < n; k++) seen.add(new HashSet<>());
    }

    private void onMessage(int k, String msg) {
        final SensorRecord record;
        try {
            record = SensorRecord.fromLine(Wire.regParts(msg)[2], partitioner.count());
            if (partitioner.of(record.getSensorId()) != k)
                throw new IllegalArgumentException("Partição errada");
        } catch (RuntimeException e) {
            System.out.println("REJEITADO [p" + k + "]: " + msg + " (" + e.getMessage() + ")");
            return;
        }
        if (record.getSensorId().startsWith("ALARM-")) return;

        long snapshotL;
        VectorClock snapshotV;
        synchronized (this) {
            // Um conjunto, em vez do maior seq, também permite receção fora de ordem.
            if (!seen.get(k).add(record.getSeq())) return;
            lamport = Math.max(lamport, record.getLamport());
            vector.merge(record.getVector()); // inclui leituras abaixo do limiar
            snapshotL = lamport;
            snapshotV = vector.copy();
        }

        if (record.getTemperature() < Wire.LIMIAR_ALARME) return;
        String sensor = "ALARM-" + record.getSensorId();
        int target = partitioner.of(sensor);
        try {
            pubSub.publish(Wire.topicIn(target),
                    Wire.in(sensor, record.getTemperature(), snapshotL, snapshotV));
            System.out.println("ALARME: " + record.getSensorId() + " p" + k + "#"
                    + record.getSeq() + " → p" + target + " L=" + snapshotL + " V=" + snapshotV);
        } catch (IOException e) {
            System.err.println("ALARME NÃO PUBLICADO: p" + k + "#" + record.getSeq() + " " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void start() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int k = 0; k < partitioner.count(); k++) {
            final int part = k;
            Thread thread = new Thread(() -> {
                try {
                    pubSub.subscribe(Wire.topicData(part), msg -> onMessage(part, msg));
                } catch (Exception e) {
                    System.err.println("SUBSCRIÇÃO FALHOU [p" + part + "]: " + e.getMessage());
                    System.exit(1);
                }
            }, "processador-p" + k);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
    }

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
