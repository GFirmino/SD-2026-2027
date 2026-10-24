package rep02;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class PartitionReplica {

    private static class PartitionState {
        private final String replicaId;
        private final int k;
        private final RecordFile file;
        private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();

        private long last;
        private String leaderHost = null;
        private int leaderPort = -1;

        private final double probPerda;
        private final Long seqDescartar;
        private boolean jaDescartouSeq = false;

        // Contadores de diagnóstico/medição para as tabelas da ficha
        private int tcpConnections = 0;
        private int tcpRecoveredRecords = 0;

        PartitionState(String replicaId, int k, double probPerda, Long seqDescartar) throws IOException {
            this.replicaId = replicaId;
            this.k = k;
            this.file = new RecordFile("replica-" + replicaId + "-p" + k + ".txt");
            this.last = this.file.lastSeq();
            this.probPerda = probPerda;
            this.seqDescartar = seqDescartar;
        }

        // Chamado apenas pela thread de subscrição desta partição (estado isolado por partição)
        void onPubSubMessage(String msg) {
            String[] parts;
            SensorRecord r;
            int port;
            try {
                parts = Wire.regParts(msg);
                port = Integer.parseInt(parts[1]);
                r = SensorRecord.fromLine(parts[2]);
            } catch (RuntimeException e) {
                System.out.println("[p" + k + "] REJEITADO: " + msg);
                return;
            }

            // Atualiza endereço e porto do líder com cada mensagem REG recebida
            this.leaderHost = parts[0];
            this.leaderPort = port;

            // Propriedades de simulação de perdas (aplicadas apenas ao PubSub)
            if (seqDescartar != null && !jaDescartouSeq && r.getSeq() == seqDescartar) {
                jaDescartouSeq = true;
                System.out.println("[p" + k + "] DESCARTADO (simulação): " + r.getSeq());
                return;
            }
            if (probPerda > 0.0 && ThreadLocalRandom.current().nextDouble() < probPerda) {
                System.out.println("[p" + k + "] DESCARTADO (simulação): " + r.getSeq());
                return;
            }

            try {
                applyRule(r, false);
            } catch (IOException e) {
                System.out.println("[p" + k + "] IO: " + e.getMessage());
            }
        }

        private void applyRule(SensorRecord r, boolean fromRecovery) throws IOException {
            if (r.getSeq() <= last || pending.containsKey(r.getSeq())) {
                System.out.println("[p" + k + "] DUPLICADO: " + r.getSeq());
                return;
            }

            if (r.getSeq() == last + 1) {
                writeRecord(r);
                drainPending();
            } else {
                // r.getSeq() > last + 1
                pending.put(r.getSeq(), r);
                System.out.println("[p" + k + "] EM ESPERA: " + r.getSeq() + " (falta o registo " + (last + 1) + ", retidos=" + pending.size() + ")");

                if (!fromRecovery && pending.size() > Wire.LIMITE_RETIDOS) {
                    recover();
                    drainPending();
                }
            }
        }

        private void writeRecord(SensorRecord r) throws IOException {
            file.append(r);
            last = r.getSeq();
            long now = System.currentTimeMillis();
            System.out.println("[p" + k + "] APLICADO: " + r.toLine() + " (t=" + now + ", retidos=" + pending.size() + ", ligTCP=" + tcpConnections + ", recTCP=" + tcpRecoveredRecords + ")");
        }

        private void drainPending() throws IOException {
            while (pending.containsKey(last + 1)) {
                SensorRecord next = pending.remove(last + 1);
                writeRecord(next);
            }
        }

        private void recover() {
            if (leaderHost == null || leaderPort <= 0) {
                System.out.println("RECUPERAÇÃO FALHOU: endereço do líder desconhecido");
                return;
            }

            long de = last + 1;
            long ate = pending.firstKey() - 1;
            if (de > ate) {
                return;
            }

            System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + leaderHost + ":" + leaderPort);
            tcpConnections++;

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
                s.setSoTimeout(Wire.T_TCP_MS);

                try (BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

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
                        tcpRecoveredRecords++;
                        System.out.println("RECUPERADO: " + rec.getSeq());
                        applyRule(rec, true);
                    }

                    if (!receivedFim) {
                        System.out.println("RECUPERAÇÃO FALHOU: resposta interrompida antes de FIM");
                    }
                }
            } catch (Exception e) {
                System.out.println("RECUPERAÇÃO FALHOU: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]");
            return;
        }

        String id = args[0];
        String[] partTokens = args[1].split(",");
        String portApi = args.length >= 3 ? args[2] : "5001";
        String api = portApi.startsWith("http") ? portApi : "http://127.0.0.1:" + portApi + "/api/v0";

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

        for (String token : partTokens) {
            int k = Integer.parseInt(token.trim());
            final double pPerda = probPerda;
            final Long sDesc = seqDescartar;

            Thread t = new Thread(() -> {
                try {
                    PartitionState state = new PartitionState(id, k, pPerda, sDesc);
                    IpfsPubSub pubSub = new IpfsPubSub(api);
                    System.out.println("Réplica " + id + " subscrita em " + Wire.topicData(k) + " via " + api + " (último seq=" + state.last + ")");
                    pubSub.subscribe(Wire.topicData(k), state::onPubSubMessage);
                } catch (Exception e) {
                    System.out.println("[p" + k + "] Erro na subscrição da réplica " + id + ": " + e.getMessage());
                }
            });
            t.start();
        }
    }
}