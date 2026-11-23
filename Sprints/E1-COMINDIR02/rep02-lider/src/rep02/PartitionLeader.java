package rep02;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PartitionLeader {
    static final String DEFAULT_HOST = "127.0.0.1";
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";

    private final int k;
    private final Partitioner partitioner;
    private final String host;
    private final int porto;
    private final IpfsPubSub ps;
    private final RecordFile file;

    // Estado partilhado entre a thread de entrada PubSub e a thread do servidor TCP
    private long seq;

    public PartitionLeader(int k, int n, String host, String api) throws IOException {
        this.k = k;
        this.partitioner = new Partitioner(n);
        this.host = host;
        this.porto = Wire.PORTO_BASE + k;
        this.ps = new IpfsPubSub(api);
        this.file = new RecordFile("lider-p" + k + ".txt");
        this.seq = this.file.lastSeq();
    }

    private synchronized long getLastSeq() {
        return seq;
    }

    private void processInMessage(String msg) {
        try {
            if (!"IN".equals(Wire.type(msg))) {
                System.out.println("REJEITADO: " + msg);
                return;
            }
            String body = Wire.body(msg);
            String[] parts = body.split(";");
            if (parts.length != 2) {
                System.out.println("REJEITADO: " + msg);
                return;
            }

            String sensor = parts[0].trim();
            if (sensor.isEmpty()) {
                System.out.println("REJEITADO: " + msg);
                return;
            }
            double temp = Double.parseDouble(parts[1].trim());

            int targetPart = partitioner.of(sensor);
            if (targetPart != k) {
                System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + targetPart);
                return;
            }

            SensorRecord r;
            // Atribuição do seq e append na mesma secção sincronizada
            synchronized (this) {
                r = SensorRecord.now(++seq, sensor, temp);
                file.append(r);
            }

            String regMsg = Wire.reg(host, porto, r);
            ps.publish(Wire.topicData(k), regMsg);
            System.out.println("Registado e publicado [p" + k + "]: " + r.toLine());

        } catch (Exception e) {
            System.out.println("REJEITADO: " + msg);
        }
    }

    public void start() throws IOException {
        Thread entrada = new Thread(() -> {
            try {
                System.out.println("Líder p" + k + " subscrito em " + Wire.topicIn(k));
                ps.subscribe(Wire.topicIn(k), this::processInMessage);
            } catch (Exception e) {
                System.out.println("Erro na subscrição de entrada p" + k + ": " + e.getMessage());
            }
        });
        entrada.setDaemon(true);
        entrada.start();

        try (ServerSocket server = new ServerSocket(porto)) {
            System.out.println("Líder p" + k + " (N=" + partitioner.count() + ") à escuta de recuperação em " + host + ":" + porto + " (seq inicial=" + getLastSeq() + ")");
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();
                    if (pedido == null) {
                        out.println("ERRO Pedido vazio");
                        continue;
                    }

                    String[] p = pedido.trim().split("\\s+");
                    if (p.length != 3 || !p[0].equals("PEDIDO")) {
                        out.println("ERRO Formato inválido");
                        continue;
                    }

                    long de, ate;
                    try {
                        de = Long.parseLong(p[1]);
                        ate = Long.parseLong(p[2]);
                    } catch (NumberFormatException e) {
                        out.println("ERRO Números de sequência inválidos");
                        continue;
                    }

                    long ultimoEscrito = getLastSeq();
                    if (ate > ultimoEscrito) {
                        ate = ultimoEscrito;
                    }

                    if (de <= 0 || de > ate) {
                        out.println("ERRO Intervalo inválido (" + de + ".." + ate + ")");
                        continue;
                    }

                    List<SensorRecord> registos = file.range(de, ate);
                    for (SensorRecord r : registos) {
                        out.println(r.toLine());
                    }
                    out.println("FIM");

                    System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para " + s.getRemoteSocketAddress() + " (" + registos.size() + " registos)");

                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }
        try {
            int k = Integer.parseInt(args[0]);
            int n = Integer.parseInt(args[1]);
            String host = args.length >= 3 ? args[2] : DEFAULT_HOST;
            String api = args.length >= 4 ? args[3] : DEFAULT_API;

            if (k < 0 || k >= n) {
                System.out.println("Partição k inválida para N=" + n);
                return;
            }

            new PartitionLeader(k, n, host, api).start();
        } catch (Exception e) {
            System.out.println("Erro fatal no líder: " + e.getMessage());
        }
    }
}