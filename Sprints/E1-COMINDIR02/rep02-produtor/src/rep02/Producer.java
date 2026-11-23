package rep02;

import java.io.IOException;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Producer {
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";

    public static void main(String[] args) {
        int nPartitions = 1;
        String api = DEFAULT_API;

        if (args.length >= 1) {
            if (args[0].startsWith("http")) {
                api = args[0];
            } else {
                nPartitions = Integer.parseInt(args[0]);
                if (args.length >= 2) {
                    api = args[1];
                }
            }
        }

        Partitioner partitioner = new Partitioner(nPartitions);
        IpfsPubSub pubSub = new IpfsPubSub(api);

        System.out.println("Produtor pronto (N=" + nPartitions + ") em " + api);
        System.out.println("Comandos: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> | sair");

        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;

                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("sair")) break;

                String[] p = line.split("\\s+");

                if (p.length == 3 && (p[0].equalsIgnoreCase("rajada") || p[0].equalsIgnoreCase("rajada-quente"))) {
                    boolean quente = p[0].equalsIgnoreCase("rajada-quente");
                    int n, s;
                    try {
                        n = Integer.parseInt(p[1]);
                        s = Integer.parseInt(p[2]);
                        if (n <= 0 || s <= 0) throw new NumberFormatException();
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
                            if (s == 1 || ThreadLocalRandom.current().nextDouble() < 0.80) {
                                sensor = "S01";
                            } else {
                                int idx = ThreadLocalRandom.current().nextInt(2, s + 1);
                                sensor = String.format("S%02d", idx);
                            }
                        } else {
                            int idx = ThreadLocalRandom.current().nextInt(1, s + 1);
                            sensor = String.format("S%02d", idx);
                        }

                        double temp = Math.round(ThreadLocalRandom.current().nextDouble(15.0, 30.0) * 100.0) / 100.0;
                        int part = partitioner.of(sensor);
                        String msg = Wire.in(sensor, temp);

                        if (i == 0) firstSend = System.currentTimeMillis();
                        pubSub.publish(Wire.topicIn(part), msg);
                        if (i == n - 1) lastSend = System.currentTimeMillis();

                        counts[part]++;
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
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }

                int part = partitioner.of(sensor);
                pubSub.publish(Wire.topicIn(part), Wire.in(sensor, temp));
                System.out.println(Wire.in(sensor, temp) + " → p" + part);
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrompido.");
        }
    }
}