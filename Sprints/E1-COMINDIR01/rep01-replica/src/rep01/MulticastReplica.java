package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    private static class State {
        private static final long BURST_END_DELAY_MS = 1500;

        private final RecordFile file;
        private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
        private final Timer burstTimer = new Timer(true);

        private long last;

        private boolean burstActive = false;
        private long simFirstReceive = 0;
        private long simLastReceive = 0;
        private long simReceived = 0;
        private long simWritten = 0;
        private TimerTask burstEndTask;

        State(RecordFile file, long last) {
            this.file = file;
            this.last = last;
        }

        synchronized void process(String line) {
            final SensorRecord r;

            try {
                r = SensorRecord.fromLine(line);
            } catch (RuntimeException e) {
                System.out.println("REJEITADO: " + line);
                return;
            }

            if ("SIM".equals(r.getSensorId())) {
                long now = System.currentTimeMillis();

                if (!burstActive) {
                    burstActive = true;
                    simFirstReceive = now;
                    simReceived = 0;
                    simWritten = 0;
                }

                simLastReceive = now;
                simReceived++;
            }

            try {
                if (r.getSeq() <= last || pending.containsKey(r.getSeq())) {
                    System.out.println("DUPLICADO: " + r.getSeq());
                } else if (r.getSeq() == last + 1) {
                    apply(r);

                    while (pending.containsKey(last + 1)) {
                        SensorRecord next = pending.remove(last + 1);
                        apply(next);
                    }
                } else {
                    pending.put(r.getSeq(), r);
                    System.out.println("EM ESPERA: " + r.getSeq() + " (falta o registo " + (last + 1) + ")");
                }

                if (!"SIM".equals(r.getSensorId())) {
                    System.out.println("ESTADO: último=" + last + " retidos=" + pending.keySet());
                } else {
                    scheduleBurstSummary();
                }

            } catch (IOException e) {
                System.out.println("IO: " + e.getMessage());
            }
        }

        private void apply(SensorRecord r) throws IOException {
            file.append(r);
            last = r.getSeq();

            if ("SIM".equals(r.getSensorId()) && burstActive) {
                simWritten++;
            } else if (!"SIM".equals(r.getSensorId())) {
                System.out.println("APLICADO: " + r.toLine());
            }
        }

        private void scheduleBurstSummary() {
            if (burstEndTask != null) {
                burstEndTask.cancel();
            }

            burstEndTask = new TimerTask() {
                @Override
                public void run() {
                    finishBurst();
                }
            };

            burstTimer.schedule(burstEndTask, BURST_END_DELAY_MS);
        }

        private synchronized void finishBurst() {
            if (!burstActive) {
                return;
            }

            long retained = pending.values().stream()
                    .filter(r -> "SIM".equals(r.getSensorId()))
                    .count();

            System.out.println("RAJADA SIM concluída:");
            System.out.println("Primeira receção >> " + simFirstReceive);
            System.out.println("Última receção >> " + simLastReceive);
            System.out.println("Recebidos >> " + simReceived);
            System.out.println("Escritos >> " + simWritten);
            System.out.println("Retidos em memória >> " + retained);

            burstActive = false;
            simFirstReceive = 0;
            simLastReceive = 0;
            simReceived = 0;
            simWritten = 0;
            burstEndTask = null;
        }
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");

        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);

            State state = new State(file, file.lastSeq());

            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT);

            byte[] buffer = new byte[1000];

            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                socket.receive(p); // bloqueia à espera de um datagrama

                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                state.process(line);
            }

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
