package server;

import interfaces.GameController;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;

public class ServerThread extends Thread {

    private DatagramSocket socket;
    private final int serverPort = 5555;
    private volatile boolean end = false;

    private static final int MAX_CLIENTS = 2;
    private int connectedClients = 0;
    private final ArrayList<Client> clients = new ArrayList<>();

    private final GameController gameController;

    private long seedPartida = 0L;
    private int nivelPartida = 1;
    private boolean partidaArrancada = false;

    public ServerThread(GameController gameController) {
        this.gameController = gameController;
        try {
            socket = new DatagramSocket(serverPort);
            System.out.println("[SERVER] Escuchando en puerto " + serverPort);
        } catch (SocketException e) {
            System.out.println("[SERVER] No se pudo abrir el puerto " + serverPort + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        while (!end) {
            DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
            try {
                socket.receive(packet);
                processMessage(packet);
            } catch (IOException e) {
                if (!end) System.out.println("[SERVER] IO: " + e.getMessage());
            }
        }
    }

    private void processMessage(DatagramPacket packet) {
        String message = new String(packet.getData(), 0, packet.getLength()).trim();
        String[] parts = message.split(":");

        int index = findClientIndex(packet);

        System.out.println("[SERVER] Recibido: " + message + " desde " + packet.getAddress() + ":" + packet.getPort());

        // CONNECT
        if ("Connect".equals(parts[0])) {

            if (index != -1) {
                sendMessage("AlreadyConnected", packet.getAddress(), packet.getPort());
                return;
            }

            if (connectedClients >= MAX_CLIENTS) {
                sendMessage("Full", packet.getAddress(), packet.getPort());
                return;
            }

            connectedClients++;
            Client newClient = new Client(connectedClients, packet.getAddress(), packet.getPort());
            clients.add(newClient);

            sendMessage("Connected:" + connectedClients, packet.getAddress(), packet.getPort());

            if (connectedClients == MAX_CLIENTS && !partidaArrancada) {
                partidaArrancada = true;

                seedPartida = System.currentTimeMillis();
                nivelPartida = 1;

                sendMessageToAll("Start:" + seedPartida + ":" + nivelPartida);

                System.out.println("[SERVER] Start enviado seed=" + seedPartida + " nivel=" + nivelPartida);

                gameController.startGame();

            }

            return;
        }

        // no connect => debe estar conectado
        if (index == -1) {
            sendMessage("NotConnected", packet.getAddress(), packet.getPort());
            return;
        }

        Client client = clients.get(index);

        switch (parts[0]) {
            case "Move": {
                // Move:dx:dy
                if (parts.length >= 3) {
                    int dx = Integer.parseInt(parts[1]);
                    int dy = Integer.parseInt(parts[2]);
                    gameController.move(client.getNum(), dx, dy);
                }
                break;
            }

            case "Spawn": {
                int id = Integer.parseInt(parts[1]);
                float px = Float.parseFloat(parts[2]);
                float py = Float.parseFloat(parts[3]);

                gameController.spawn(id, px, py);
                break;
            }

            case "Door": {
                // ✅ soporta ambos formatos:
                // Viejo: Door:ORIGEN:DESTINO:DIR  (len 4)
                // Nuevo: Door:PLAYER:ORIGEN:DESTINO:DIR (len 5)

                int playerNum = client.getNum();
                String origen;
                String destino;
                String dir;

                if (parts.length >= 5) {
                    // nuevo
                    playerNum = Integer.parseInt(parts[1]);
                    origen = parts[2];
                    destino = parts[3];
                    dir = parts[4];
                } else if (parts.length >= 4) {
                    // viejo
                    origen = parts[1];
                    destino = parts[2];
                    dir = parts[3];
                } else {
                    System.out.println("[SERVER] Door mal formado: " + message);
                    break;
                }

                System.out.println("[SERVER] Door OK -> P" + playerNum + " " + origen + " -> " + destino + " (" + dir + ")");
                gameController.door(playerNum, origen, destino, dir);
                break;
            }
        }
    }

    private int findClientIndex(DatagramPacket packet) {
        String id = packet.getAddress().toString() + ":" + packet.getPort();
        for (int i = 0; i < clients.size(); i++) {
            if (id.equals(clients.get(i).getId())) return i;
        }
        return -1;
    }

    public void sendMessage(String message, InetAddress clientIp, int clientPort) {
        byte[] data = message.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, clientIp, clientPort);
        try {
            socket.send(packet);
        } catch (IOException e) {
            System.out.println("[SERVER] Send error: " + e.getMessage());
        }
    }

    public void sendMessageToAll(String message) {
        for (Client client : clients) {
            sendMessage(message, client.getIp(), client.getPort());
        }
    }

    public void terminate() {
        end = true;
        if (socket != null && !socket.isClosed()) socket.close();
        interrupt();
    }
}
