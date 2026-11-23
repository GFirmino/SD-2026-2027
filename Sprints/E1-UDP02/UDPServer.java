
import java.io.*;
import java.net.*;
import java.util.*;

public class UDPServer {
    private static final List<String> receivedMessages = new ArrayList<>();
    private static final Map<Integer, String> temporaryMessages = new HashMap<>();

    /**
     * Processes delivered messages.
     * @return the last message processed in order
     */
    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage) {
        if (nCurrentMessage == nLastMessageInOrder + 1) {
            receivedMessages.add(currentMessage);
            nLastMessageInOrder = nCurrentMessage;

            // Entregar em cascata as mensagens consecutivas já guardadas.
            while (temporaryMessages.containsKey(nLastMessageInOrder + 1)) {
                nLastMessageInOrder++;
                receivedMessages.add(temporaryMessages.remove(nLastMessageInOrder));
            }
        } else {
            // Guarda também números repetidos.
            temporaryMessages.put(nCurrentMessage, currentMessage);
        }

        return nLastMessageInOrder;
    }

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
                int deliveredBefore = receivedMessages.size();

                try {
                    if (!message.matches("\\d+,.+")) {
                        output = "error,invalid format";
                        System.out.println("[SERVER] Mensagem inválida recebida >> " + message);
                    } else {
                        String[] parts = message.split(",", 2);

                        int currentMessage = Integer.parseInt(parts[0]);
                        String text = parts[1];
                        int previousL = lastMessage;

                        lastMessage = processDeliveredMessages(lastMessage, currentMessage, message);

                        if (lastMessage > previousL) {
                            output = currentMessage + "," + text;
                            System.out.println("[SERVER] Mensagem aceite (N=" + currentMessage + " -> " + text + ")");
                        } else {
                            output = "waitingfor," + (lastMessage + 1);
                            System.out.println("[SERVER] Mensagem fora de ordem/repetida guardada >> " + message);
                        }
                    }
                } catch (NumberFormatException e) {
                    output = "error,invalid format";
                    System.out.println("[SERVER] Mensagem inválida recebida >> " + message);
                }

                System.out.println("[SERVER] Valor de L atual >> " + lastMessage);
                System.out.println("[SERVER] Mensagens entregues neste passo >> " + receivedMessages.subList(deliveredBefore, receivedMessages.size()));
                System.out.println("[SERVER] Estrutura temporária >> " + new TreeMap<>(temporaryMessages));
                System.out.println("[SERVER] Lista de receção >> " + receivedMessages);

                byte[] response = output.getBytes();
                DatagramPacket reply = new DatagramPacket(response, response.length, request.getAddress(), request.getPort());
                aSocket.send(reply);
            }
        } catch (SocketException e) { System.out.println("[SERVER] [ERRO] Socket >> " + e.getMessage());
        } catch (IOException e)     { System.out.println("[SERVER] [ERRO] IO >> " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}