package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) {
        Socket s = null;

        try {
            int serverPort = 7896;
            s = new Socket("localhost", serverPort);

            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream()); // O cliente envia objetos
            DataInputStream in = new DataInputStream(s.getInputStream()); // O servidor responde com texto

            Place place = new Place("3500-000", "Viseu");
            Person person = new Person("Pessoa de Teste", place, 2000);

            out.writeObject(person);
            out.flush();

            String data = in.readUTF(); // Bloqueia à espera da resposta do servidor

            System.out.println("[CLIENT] Received >> " + data);
        } catch (UnknownHostException e) {
            System.out.println("[CLIENT] Sock >> " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("[CLIENT] EOF >> " + e.getMessage());
        } catch (IOException e) {
            System.out.println("[CLIENT] IO >> " + e.getMessage());
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    System.out.println("[CLIENT] Close >> " + e.getMessage());
                }
            }
        }
    }
}