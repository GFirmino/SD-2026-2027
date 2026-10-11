package rep01;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";
    static final String DEFAULT_API = "http://127.0.0.1:5001/api/v0";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : DEFAULT_API;

        try (Scanner sc = new Scanner(System.in)) {
            IpfsPubSub pubSub = new IpfsPubSub(api);
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();

            System.out.println("Líder PubSub pronto em " + api);
            System.out.println("Formato: <sensor> <temperatura> | rajada <n> | sair");

            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break; // bloqueia à espera do utilizador

                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;

                String[] p = line.split("\\s+");

                if (p.length == 2 && p[0].equalsIgnoreCase("rajada")) {
                    int n;

                    try {
                        n = Integer.parseInt(p[1]);
                        if (n <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        System.out.println("Número da rajada inválido.");
                        continue;
                    }

                    long firstSend = 0;
                    long lastSend = 0;

                    for (int i = 0; i < n; i++) {
                        double temp = ThreadLocalRandom.current().nextDouble(15.0, 30.0);
                        SensorRecord r = SensorRecord.now(++seq, "SIM", temp);

                        file.append(r);

                        if (i == 0) firstSend = System.currentTimeMillis();
                        pubSub.publish(TOPIC, r.toLine()); // bloqueia enquanto espera pela resposta HTTP do daemon
                        if (i == n - 1) lastSend = System.currentTimeMillis();
                    }

                    long elapsed = Math.max(1, lastSend - firstSend);
                    double throughput = n * 1000.0 / elapsed;

                    System.out.println("RAJADA concluída:");
                    System.out.println("Primeiro envio >> " + firstSend);
                    System.out.println("Último envio >> " + lastSend);
                    System.out.println("Tempo entre primeiro e último envio (ms) >> " + (lastSend - firstSend));
                    System.out.printf("Débito aproximado (registos/s) >> %.2f%n", throughput);
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }

                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }

                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r);
                pubSub.publish(TOPIC, r.toLine()); // bloqueia enquanto espera pela resposta HTTP do daemon

                System.out.println("Registado e publicado: " + r.toLine());
            }

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrompido.");
        }
    }
}
