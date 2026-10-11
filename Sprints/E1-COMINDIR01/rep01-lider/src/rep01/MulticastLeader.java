package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {

            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1);

            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();

            System.out.println("Líder multicast pronto.");
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

                        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);

                        if (i == 0) firstSend = System.currentTimeMillis();
                        socket.send(new DatagramPacket(m, m.length, group, PORT));
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

                byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(m, m.length, group, PORT));

                System.out.println("Registado e enviado: " + r.toLine());
            }

        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }
}
