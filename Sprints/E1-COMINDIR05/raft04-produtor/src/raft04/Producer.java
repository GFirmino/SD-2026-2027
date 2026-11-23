package raft04;

import java.io.IOException;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Producer {
    static final String DEFAULT_API = Wire.API;

    // Este método serve para avisar quando uma leitura vai ser enviada para um líder suspeito.
    private static void warn(HeartbeatMonitor monitor, int k, String sensor) {
        if (monitor.state() == HeartbeatMonitor.State.SUSPEITO)
            System.out.println(
                    "AVISO: particao p" + k + " sem lider conhecido; leitura " + sensor + " pode perder-se");
    }

    // Este método serve para acompanhar os líderes e publicar leituras ou rajadas introduzidas pelo
    // utilizador.
    public static void main(String[] args) {
        int nPartitions = Wire.DEFAULT_N;
        String api = DEFAULT_API;

        try {
            if (args.length < 1 || args.length > 2)
                throw new IllegalArgumentException("Uso: Producer <N> [api]");
            nPartitions = Integer.parseInt(args[0]);

            if (nPartitions < 1) throw new IllegalArgumentException("N deve ser positivo");

            if (args.length >= 2) {
                api = args[1];
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);

            return;
        }

        Partitioner partitioner = new Partitioner(nPartitions);
        IpfsPubSub pubSub = new IpfsPubSub(api);
        ClientMonitor log = new ClientMonitor("produtor");
        EventLog sent = new EventLog("produtor.txt");
        HeartbeatMonitor[] monitors = new HeartbeatMonitor[nPartitions];

        for (int k = 0; k < nPartitions; k++) {
            final int part = k;

            monitors[k] =
                    new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, log::event);
            Thread listener =
                    new Thread(
                            () -> {
                                try {
                                    pubSub.subscribe(
                                            Wire.topicData(part),
                                            (msg) -> {
                                                try {
                                                    Batch b = Batch.header(msg);

                                                    monitors[part].onHeartbeat(b.term, b.leader, b.hb);
                                                    log.received(part, b);
                                                } catch (IllegalArgumentException e) {
                                                    System.err.println("LOTE REJEITADO: " + e);
                                                }
                                            });
                                } catch (Exception e) {
                                    System.err.println("SUBSCRICAO FALHOU: " + e);
                                    System.exit(1);
                                }
                            },
                            "produtor-p" + k);
            listener.setDaemon(true);
            listener.start();
        }

        ClientMonitor.schedule(monitors);
        System.out.println("Produtor pronto (N=" + nPartitions + ") em " + api);
        System.out.println(
                "Comandos: <sensor> <temperatura> | rajada <n> <intervalo-ms> | rajada-quente <n>"
                        + " <intervalo-ms> | sair");

        try (Scanner sc = new Scanner(System.in); ) {
            while (true) {
                System.out.print("> ");

                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();

                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");

                if (p.length == 3
                        && (p[0].equalsIgnoreCase("rajada")
                                || p[0].equalsIgnoreCase("rajada-quente"))) {
                    boolean quente = p[0].equalsIgnoreCase("rajada-quente");
                    int n;
                    int s;

                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);

                        if (n <= 0 || s < 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        System.out.println("Parâmetros de rajada inválidos.");
                        continue;
                    }

                    int[] counts = new int[nPartitions];
                    long firstSend = 0;
                    long lastSend = 0;

                    for (int i = 0; i < n; i++) {
                        String sensor;

                        if (quente) {
                            if (ThreadLocalRandom.current().nextDouble() < 0.8) {
                                sensor = "S01";
                            } else {
                                int idx = ThreadLocalRandom.current().nextInt(2, 11);

                                sensor = String.format("S%02d", idx);
                            }
                        } else {
                            int idx = ThreadLocalRandom.current().nextInt(1, 11);

                            sensor = String.format("S%02d", idx);
                        }

                        double temp =
                                Math.round(
                                                ThreadLocalRandom.current().nextDouble(15.0, 30.0)
                                                        * 100.0)
                                        / 100.0;
                        int part = partitioner.of(sensor);
                        String msg = Wire.in(sensor, temp, 0, new VectorClock(nPartitions));

                        if (i == 0) firstSend = System.currentTimeMillis();
                        warn(monitors[part], part, sensor);
                        pubSub.publish(Wire.topicIn(part), msg);
                        sent.event("p"+part+";"+sensor+";"+temp);

                        if (i == n - 1) lastSend = System.currentTimeMillis();
                        counts[part]++;

                        if (i + 1 < n) Thread.sleep(s);
                    }

                    long elapsed = Math.max(1, lastSend - firstSend);
                    double throughput = n * 1000.0 / elapsed;

                    System.out.println("RAJADA concluída:");
                    System.out.println("Primeira publicação (ms) >> " + firstSend);
                    System.out.println("Última publicação (ms)   >> " + lastSend);
                    System.out.println("Tempo total de envio (ms)>> " + (lastSend - firstSend));
                    System.out.printf(Locale.US, "Débito de envio (msg/s)  >> %.2f%n", throughput);
                    StringBuilder sb = new StringBuilder("Leituras por partição    >> ");

                    for (int k = 0; k < nPartitions; k++) {
                        sb.append("p").append(k).append("=").append(counts[k]).append(" ");
                    }

                    System.out.println(sb.toString().trim());
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }

                String sensor = p[0];
                double temp;

                try {
                    temp = Double.parseDouble(p[1]);
                    Wire.validateReading(sensor, temp);
                } catch (IllegalArgumentException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }

                int part = partitioner.of(sensor);
                String message = Wire.in(sensor, temp, 0, new VectorClock(nPartitions));

                warn(monitors[part], part, sensor);
                pubSub.publish(Wire.topicIn(part), message);
                sent.event("p"+part+";"+sensor+";"+temp);
                System.out.println(message + " → p" + part);
            }
        } catch (IOException e) {
            System.err.println("IO: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrompido.");
        }
    }
}
