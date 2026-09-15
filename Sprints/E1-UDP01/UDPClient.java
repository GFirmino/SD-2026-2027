import java.io.*;
import java.net.*;
import java.util.Scanner;

public class UDPClient {
    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner scanner = new Scanner(System.in);

        try {
            aSocket = new DatagramSocket();

            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            System.out.println("Escolha o modo de numeração:");
            System.out.println("1 - Automático");
            System.out.println("2 - Manual");

            int mode = -1;

            while (mode != 1 && mode != 2) {
                System.out.print("Modo >> ");
                String input = scanner.nextLine();

                try {
                    mode = Integer.parseInt(input);
                } catch (NumberFormatException e) {}

                if(mode != 1 && mode != 2)
                    System.out.println("[ERRO] Opção inválida. Escolha 1 ou 2.");
            }

            int seqNum = 1;
            String text = "";

            while(!text.equalsIgnoreCase("sair")){
                System.out.println("\nEscreva a mensagem a enviar.");
                System.out.println("Para sair, escreva 'sair'.");
                System.out.print("Mensagem >> ");
                text = scanner.nextLine();

                if(text.equalsIgnoreCase("sair")) continue;

                if (text.isEmpty()) {
                    System.out.println("[ERRO] A mensagem não pode estar vazia.");
                    continue;
                }

                int curSeq;

                if(mode == 1){
                    //Automático
                    curSeq = seqNum;
                    seqNum++;
                }else{
                    //Manual
                    while (true) {
                        System.out.print("Número de sequência >> ");
                        String input = scanner.nextLine();

                        try {
                            curSeq = Integer.parseInt(input);
                            if (curSeq > 0) break;
                        } catch (NumberFormatException e) {}

                        System.out.println("[ERRO] Número de sequência inválido.");
                    }
                }

                String message = curSeq + "," + text;

                if (!message.matches("\\d+,.+")) {
                    System.out.println("[ERRO] Formato inválido. Utilize: numero,texto.");
                    return;
                }

                byte[] m = message.getBytes();

                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                byte[] buffer = new byte[1000];

                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(reply);

                String response = new String(reply.getData(), 0, reply.getLength());

                if (response.startsWith("waitingfor,")) {
                    String[] parts = response.split(",", 2);
                    System.out.println("[WAITING FOR] O servidor está à espera da mensagem nº" + parts[1]);
                } else if (response.equals("error,invalid format")) {
                    System.out.println("[ERRO] Mensagem inválida.");
                } else {
                    System.out.println("[ECHO] " + response);
                }
            }

            System.out.println("\nCliente terminado.");
        } catch (SocketException e) {
            System.out.println("Socket >> " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO >> " + e.getMessage());
        } finally {
            if (aSocket != null)
                aSocket.close();
        }

        scanner.close();
    }
}