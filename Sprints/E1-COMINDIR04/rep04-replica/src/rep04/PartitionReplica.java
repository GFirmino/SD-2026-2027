package rep04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class PartitionReplica {
    private static class PartitionState {
        private final int k;
        private final RecordFile file;
        private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
        private final MonitorLog log;
        private final HeartbeatMonitor monitor;
        private long last;
        private String leaderHost = null;
        private int leaderPort = -1;
        private final double probPerda;
        private final Long seqDescartar;
        private boolean jaDescartouSeq = false;
        private int tcpConnections = 0;
        private int tcpRecoveredRecords = 0;

        // Este método serve para preparar o ficheiro, o monitor e o estado de uma partição da
        // réplica.
        PartitionState(String replicaId, int k, double probPerda, Long seqDescartar)
                throws IOException {
            this.k = k;
            this.log = new MonitorLog("replica" + replicaId);
            this.monitor =
                    new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
            this.file = new RecordFile("replica-" + replicaId + "-p" + k + ".txt");
            this.last = this.file.lastSeq();
            this.probPerda = probPerda;
            this.seqDescartar = seqDescartar;
        }

        // Este método serve para receber um lote, atualizar o monitor e recuperar registos em
        // falta.
        void onPubSubMessage(String msg) {
            try {
                Batch batch = Batch.decode(msg, Wire.DEFAULT_N);

                for (SensorRecord r : batch.records)
                    if (new Partitioner(Wire.DEFAULT_N).of(r.getSensorId()) != k)
                        throw new IllegalArgumentException("Particao errada");

                if (seqDescartar != null && !jaDescartouSeq && batch.hb == seqDescartar) {
                    jaDescartouSeq = true;
                    System.out.println("DESCARTADO LOTE hb=" + batch.hb);

                    return;
                }

                if (probPerda > 0 && ThreadLocalRandom.current().nextDouble() < probPerda) return;
                monitor.onHeartbeat(batch.inc, batch.hb);
                log.received(k, batch);
                leaderHost = batch.address;
                leaderPort = batch.port;

                for (SensorRecord r : batch.records) applyRule(r, false);

                if (last < batch.lastSeq) recover(batch.lastSeq);
                drainPending();
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("[p" + k + "] LOTE REJEITADO/IO: " + e.getMessage());
            }
        }

        // Este método serve para aplicar o próximo registo e reter os que chegam adiantados.
        private void applyRule(SensorRecord r, boolean fromRecovery) throws IOException {
            if (r.getSeq() <= last || pending.containsKey(r.getSeq())) {
                System.out.println("[p" + k + "] DUPLICADO: " + r.getSeq());

                return;
            }

            if (r.getSeq() == last + 1) {
                writeRecord(r);
                drainPending();
            } else {
                pending.put(r.getSeq(), r);
                System.out.println(
                        "[p"
                                + k
                                + "] EM ESPERA: "
                                + r.getSeq()
                                + " (falta o registo "
                                + (last + 1)
                                + ", retidos="
                                + pending.size()
                                + ")");

                if (!fromRecovery && pending.size() > Wire.LIMITE_RETIDOS) {
                    recover(pending.firstKey() - 1);
                    drainPending();
                }
            }
        }

        // Este método serve para persistir um registo e avançar a sequência aplicada.
        private void writeRecord(SensorRecord r) throws IOException {
            file.append(r);
            last = r.getSeq();
            long now = System.currentTimeMillis();

            System.out.println(
                    "[p"
                            + k
                            + "] APLICADO: "
                            + r.toLine()
                            + " (t="
                            + now
                            + ", retidos="
                            + pending.size()
                            + ", ligTCP="
                            + tcpConnections
                            + ", recTCP="
                            + tcpRecoveredRecords
                            + ")");
        }

        // Este método serve para aplicar os registos retidos que passaram a ser consecutivos.
        private void drainPending() throws IOException {
            while (pending.containsKey(last + 1)) {
                SensorRecord next = pending.get(last + 1);

                writeRecord(next);
                pending.remove(next.getSeq());
            }
        }

        // Este método serve para pedir um intervalo por TCP e aplicar os registos recuperados.
        private void recover(long ate) {
            if (leaderHost == null || leaderPort <= 0) {
                System.out.println("RECUPERAÇÃO FALHOU: endereço do líder desconhecido");

                return;
            }

            long de = last + 1;

            if (de > ate) {
                return;
            }

            System.out.println(
                    "RECUPERAÇÃO: "
                            + de
                            + ".."
                            + ate
                            + " pedida a "
                            + leaderHost
                            + ":"
                            + leaderPort);
            log.event("RECUPERACAO;p" + k + ";de=" + de + ";ate=" + ate);
            tcpConnections++;

            try (Socket s = new Socket(); ) {
                s.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
                s.setSoTimeout(Wire.T_TCP_MS);

                try (BufferedReader in =
                                new BufferedReader(
                                        new InputStreamReader(
                                                s.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out =
                                new PrintWriter(
                                        new OutputStreamWriter(
                                                s.getOutputStream(), StandardCharsets.UTF_8),
                                        true); ) {
                    out.println("PEDIDO " + de + " " + ate);
                    String line;
                    boolean receivedFim = false;

                    while ((line = in.readLine()) != null) {
                        line = line.trim();

                        if (line.isEmpty()) continue;

                        if (line.equals("FIM")) {
                            receivedFim = true;
                            break;
                        }

                        if (line.startsWith("ERRO")) {
                            System.out.println("RECUPERAÇÃO FALHOU: " + line);

                            return;
                        }

                        SensorRecord rec = SensorRecord.fromLine(line);

                        if (rec.getSeq() < de
                                || rec.getSeq() > ate
                                || new Partitioner(Wire.DEFAULT_N).of(rec.getSensorId()) != k)
                            throw new IOException("Registo fora do intervalo/partição pedidos");
                        tcpRecoveredRecords++;
                        System.out.println("RECUPERADO: " + rec.getSeq());
                        applyRule(rec, true);
                    }

                    if (!receivedFim) {
                        System.out.println(
                                "RECUPERAÇÃO FALHOU: resposta interrompida antes de FIM");
                    }
                }
            } catch (Exception e) {
                System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage());
            }
        }
    }

    // Este método serve para criar as subscrições e os monitores das partições atribuídas à
    // réplica.
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]");

            return;
        }

        String id = args[0];
        String[] partTokens = args[1].split(",");
        String portApi = args.length >= 3 ? args[2] : "5001";

        String api =
                portApi.startsWith("http") ? portApi : "http://127.0.0.1:" + portApi + "/api/v0";
        double probPerda = 0.0;
        String propPerda = System.getProperty("perda");

        if (propPerda != null) {
            probPerda = Double.parseDouble(propPerda);
        }

        Long seqDescartar = null;
        String propDescartar = System.getProperty("descartar");

        if (propDescartar != null) {
            seqDescartar = Long.parseLong(propDescartar);
        }

        if (!id.matches("[A-Za-z0-9_]+")
                || !Double.isFinite(probPerda)
                || probPerda < 0
                || probPerda > 1
                || (seqDescartar != null && seqDescartar < 1)) {
            throw new IllegalArgumentException("Identificador ou propriedades de perda inválidos");
        }

        Set<Integer> partitions = new LinkedHashSet<>();

        for (String token : partTokens) {
            int k = Integer.parseInt(token.trim());

            if (k < 0 || k >= Wire.DEFAULT_N || !partitions.add(k))
                throw new IllegalArgumentException("Partição inválida ou repetida: " + k);
        }

        List<HeartbeatMonitor> monitors = new ArrayList<>();

        for (int k : partitions) {
            final double pPerda = probPerda;
            final Long sDesc = seqDescartar;
            PartitionState state = new PartitionState(id, k, pPerda, sDesc);

            monitors.add(state.monitor);
            Thread t =
                    new Thread(
                            () -> {
                                try {
                                    IpfsPubSub pubSub = new IpfsPubSub(api);

                                    System.out.println(
                                            "Réplica "
                                                    + id
                                                    + " subscrita em "
                                                    + Wire.topicData(k)
                                                    + " via "
                                                    + api
                                                    + " (último seq="
                                                    + state.last
                                                    + ")");
                                    pubSub.subscribe(Wire.topicData(k), state::onPubSubMessage);
                                } catch (Exception e) {
                                    System.out.println(
                                            "[p"
                                                    + k
                                                    + "] Erro na subscrição da réplica "
                                                    + id
                                                    + ": "
                                                    + e.getMessage());
                                    System.exit(1);
                                }
                            },
                            "replica-" + id + "-p" + k);
            t.start();
        }

        MonitorLog.schedule(monitors.toArray(new HeartbeatMonitor[0]));
    }
}
