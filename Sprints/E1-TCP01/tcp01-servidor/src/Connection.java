package tcp01;

import java.io.*;
import java.net.*;

public class Connection extends Thread {
    private Socket clientSocket;

    public Connection(Socket aClientSocket) {
        clientSocket = aClientSocket;
        this.start(); //inicia numa nova thread
    }

    @Override
    public void run() {
        try {
            ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());

            Object object = in.readObject(); // Bloqueia enquanto espera pelo objeto

            Person person = (Person) object;

            System.out.println("[SERVER] Person recebida:");
            System.out.println("[SERVER] Nome >> " + person.getName());
            System.out.println("[SERVER] Ano >> " + person.getYear());
            System.out.println("[SERVER] Código Postal >> " + person.getPlace().getPostalCode());
            System.out.println("[SERVER] Localidade >> " + person.getPlace().getLocality());

            out.writeUTF(person.getPlace().getLocality()); //mostrar localidade ao cliente
            out.flush();
        } catch (ClassNotFoundException e) {
            System.out.println("[SERVER] Class not found >> " + e.getMessage());
        } catch (ClassCastException e) {
            System.out.println("[SERVER] Invalid object >> " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("[SERVER] EOF >> " + e.getMessage());
        } catch (IOException e) {
            System.out.println("[SERVER] IO >> " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("[SERVER] Close >> " + e.getMessage());
            }
        }
    }
}