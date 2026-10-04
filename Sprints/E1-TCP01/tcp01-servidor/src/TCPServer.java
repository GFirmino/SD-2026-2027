package tcp01;

import java.io.*;
import java.net.*;

public class TCPServer {
    public static void main(String[] args) {
        try {
            int serverPort = 7896;

            ServerSocket listenSocket = new ServerSocket(serverPort);

            System.out.println("[SERVER] Servidor iniciado na porta " + serverPort);

            while (true) {
                Socket clientSocket = listenSocket.accept(); // Bloqueia enquanto espera por um cliente

                System.out.println("[SERVER] Cliente ligado >> " + clientSocket.getInetAddress() + ":" + clientSocket.getPort());

                new Connection(clientSocket); //cada cliente numa thread
            }
        } catch (IOException e) {
            System.out.println("[SERVER] Listen >> " + e.getMessage());
        }
    }
}