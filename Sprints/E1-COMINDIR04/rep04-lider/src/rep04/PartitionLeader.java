package rep04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class PartitionLeader {
    static final String DEFAULT_HOST = "127.0.0.1";
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";
    private final int k;
    private final Partitioner partitioner;
    private final String host;
    private final int porto;
    private final IpfsPubSub ps;
    private final RecordFile file;
    private final long inc = System.currentTimeMillis();
    private long hb;
    private List<SensorRecord> pendentes = new ArrayList<>();
    private long seq;
    private long lamport;
    private VectorClock vector;

    // Este método serve para preparar o líder e restaurar seq, Lamport e vetor a partir do
    // ficheiro.
    public PartitionLeader(int k, int n, String host, String api) throws IOException {
        this.k = k;
        this.partitioner = new Partitioner(n);
        this.host = host;
        this.porto = Wire.PORTO_BASE + k;
        this.ps = new IpfsPubSub(api);

        if (k < 0 || k >= n) throw new IllegalArgumentException("Partição inválida");
        this.file = new RecordFile("lider-p" + k + ".txt", n);
        SensorRecord last = file.last();

        this.seq = last == null ? 0 : last.getSeq();
        this.lamport = last == null ? 0 : last.getLamport();
        this.vector = last == null ? new VectorClock(n) : last.getVector();

        if (vector.get(k) != seq) throw new IllegalStateException("Histórico com V[k] != seq");
    }

    // Este método serve para consultar de forma sincronizada o último número de sequência.
    private synchronized long getLastSeq() {
        return seq;
    }

    // Este método serve para validar uma entrada, guardar o evento e acrescentá-lo ao lote
    // pendente.
    private void processInMessage(String msg) {
        try {
            String[] parts = Wire.inParts(msg);
            String sensor = parts[0];
            double temp = Double.parseDouble(parts[1].trim());

            Wire.validateReading(sensor, temp);
            long receivedL = Long.parseLong(parts[2]);

            if (receivedL < 0) throw new IllegalArgumentException("Lamport negativo");
            VectorClock receivedV = VectorClock.parse(parts[3], partitioner.count());
            int targetPart = partitioner.of(sensor);

            if (targetPart != k) {
                System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + targetPart);

                return;
            }

            SensorRecord r;

            synchronized (this) {
                long nextL = Math.addExact(Math.max(lamport, receivedL), 1);
                VectorClock nextV = vector.copy();

                nextV.merge(receivedV);
                nextV.tick(k);
                long nextSeq = Math.addExact(seq, 1);

                if (nextV.get(k) != nextSeq) throw new IllegalArgumentException("V[k] != seq");
                r = SensorRecord.now(nextSeq, sensor, temp, nextL, nextV);
                file.append(r);
                seq = nextSeq;
                lamport = nextL;
                vector = nextV;
                pendentes.add(r);
                System.out.println("EVENTO: seq=" + seq + " L=" + lamport + " V=" + vector);
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Falha a escrever o ficheiro do líder", e);
        }
    }

    // Este método serve para fechar o lote com um snapshot coerente e publicá-lo fora do bloqueio.
    private void enviarLote() {
        Batch batch;

        synchronized (this) {
            List<SensorRecord> closed = pendentes;

            pendentes = new ArrayList<>();
            batch = new Batch(host, porto, inc, ++hb, seq, closed);
        }

        try {
            String message = batch.encode();
            long instant = System.currentTimeMillis();

            ps.publish(Wire.topicData(k), message);
            Files.writeString(
                    Path.of("hb-p" + k + ".txt"),
                    inc
                            + ";"
                            + batch.hb
                            + ";"
                            + instant
                            + ";"
                            + batch.lastSeq
                            + ";"
                            + batch.count
                            + ";"
                            + message.getBytes(StandardCharsets.UTF_8).length
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            System.out.println(
                    "HEARTBEAT: hb="
                            + batch.hb
                            + " n="
                            + batch.count
                            + " ultimoSeq="
                            + batch.lastSeq);
        } catch (Exception e) {
            System.err.println("LOTE FALHOU: " + e);

            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    // Este método serve para iniciar a receção de entradas, os heartbeats e o servidor TCP.
    public void start() throws IOException {
        try (ServerSocket server = new ServerSocket(); ) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(porto));
            System.out.println(
                    "Líder p"
                            + k
                            + " (N="
                            + partitioner.count()
                            + ") à escuta de recuperação em "
                            + host
                            + ":"
                            + porto
                            + " (seq inicial="
                            + getLastSeq()
                            + ", L="
                            + lamport
                            + ", V="
                            + vector
                            + ")");
            Thread entrada =
                    new Thread(
                            () -> {
                                try {
                                    System.out.println(
                                            "Líder p" + k + " subscrito em " + Wire.topicIn(k));
                                    ps.subscribe(Wire.topicIn(k), this::processInMessage);
                                } catch (Exception e) {
                                    System.err.println(
                                            "Erro na subscrição de entrada p"
                                                    + k
                                                    + ": "
                                                    + e.getMessage());
                                    System.exit(1);
                                }
                            },
                            "entrada-p" + k);
            entrada.setDaemon(true);
            entrada.start();
            ScheduledExecutorService scheduler =
                    Executors.newSingleThreadScheduledExecutor(
                            (r) -> {
                                Thread t = new Thread(r, "envio-lotes");

                                t.setDaemon(true);

                                return t;
                            });
            scheduler.scheduleAtFixedRate(
                    this::enviarLote, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);

            while (true) {
                try (Socket s = server.accept();
                        BufferedReader in =
                                new BufferedReader(
                                        new InputStreamReader(
                                                s.getInputStream(), StandardCharsets.UTF_8));
                        PrintWriter out =
                                new PrintWriter(
                                        new OutputStreamWriter(
                                                s.getOutputStream(), StandardCharsets.UTF_8),
                                        true); ) {
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

                    long de;
                    long ate;

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
                    System.out.println(
                            "RECUPERAÇÃO: "
                                    + de
                                    + ".."
                                    + ate
                                    + " para "
                                    + s.getRemoteSocketAddress()
                                    + " ("
                                    + registos.size()
                                    + " registos)");
                } catch (IOException e) {
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }

    // Este método serve para ler os argumentos e arrancar o líder da partição escolhida.
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
            System.err.println("Erro fatal no líder: " + e.getMessage());
            System.exit(1);
        }
    }
}
