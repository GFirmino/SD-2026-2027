import java.io.*;
import java.net.*;

public class UDPServer {
    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            int lastMessage = 0;

            System.out.println("[SERVER] Servidor iniciado na porta 6789.");

            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String message = new String(request.getData(), 0, request.getLength());
                String output;

                try{
                    if (!message.matches("\\d+,.+")) {
                        output = "error,invalid format";
                        System.out.println("[SERVER] Mensagem inválida recebida >> " + message);
                    } else {
                        String[] parts = message.split(",", 2);

                        int currentMessage = Integer.parseInt(parts[0]);
                        String text = parts[1];

                        if (currentMessage == lastMessage + 1) {
                            lastMessage = currentMessage;
                            output = currentMessage + "," + text;
                            System.out.println("[SERVER] Mensagem aceite (L=" + currentMessage + " -> " + text + ")");
                        } else {
                            output = "waitingfor," + (lastMessage + 1);
                            System.out.println("[SERVER] Mensagem fora de ordem.");
                        }

                        System.out.println("[SERVER] Valor de L atual >> " + lastMessage);
                    }
                }catch (NumberFormatException e) {
                    output = "error,invalid format";
                    System.out.println("[SERVER] Mensagem inválida recebida >> " + message);
                }

                byte[] response = output.getBytes();

                DatagramPacket reply = new DatagramPacket(response, response.length, request.getAddress(), request.getPort());
                aSocket.send(reply);
            }
        } catch (SocketException e) { System.out.println("[SERVER] [ERRO] Socket >> " + e.getMessage());
        } catch (IOException e)     { System.out.println("[SERVER] [ERRO] IO >> " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}