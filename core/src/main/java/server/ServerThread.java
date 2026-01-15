package server;

import interfaces.GameController;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;

public class ServerThread extends Thread {

    // ===== Constantes =====
    private static final int SERVER_PORT = 5555;
    private static final int BUFFER_SIZE = 1024;
    private static final int MAX_CLIENTS = 2;
    private static final String SEP = ":";

    // Comandos
    private static final String CMD_CONNECT = "Connect";
    private static final String CMD_MOVE = "Move";
    private static final String CMD_SPAWN = "Spawn";
    private static final String CMD_DOOR = "Door";

    private static final String MSG_ALREADY_CONNECTED = "AlreadyConnected";
    private static final String MSG_FULL = "Full";
    private static final String MSG_NOT_CONNECTED = "NotConnected";

    private DatagramSocket socket;
    private volatile boolean end = false;

    private int connectedClients = 0;
    private final ArrayList<Client> clients = new ArrayList<>();

    private final GameController gameController;

    private long seedPartida = 0L;
    private int nivelPartida = 1;
    private boolean partidaArrancada = false;

    // Reutilizable para no allocar cada loop
    private final byte[] buffer = new byte[BUFFER_SIZE];

    public ServerThread(GameController gameController) {
        super("ServerThread");
        this.gameController = gameController;

        try {
            socket = new DatagramSocket(SERVER_PORT);
            System.out.println("[SERVER] Escuchando en puerto " + SERVER_PORT);
        } catch (SocketException e) {
            System.out.println("[SERVER] No se pudo abrir el puerto " + SERVER_PORT + ": " + e.getMessage());
        }
    }

    @Override
    public void run() {
        if (socket == null) return;

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (!end) {
            try {
                socket.receive(packet);
                processMessage(packet);
            } catch (SocketException se) {
                // normal cuando cerrás el socket en terminate()
                if (!end) System.out.println("[SERVER] Socket: " + se.getMessage());
            } catch (IOException e) {
                if (!end) System.out.println("[SERVER] IO: " + e.getMessage());
            } catch (Exception e) {
                // ✅ evita que el server thread muera por mensajes raros
                if (!end) System.out.println("[SERVER] Unexpected: " + e.getMessage());
            }
        }
    }

    private void processMessage(DatagramPacket packet) {
        final String message = new String(packet.getData(), 0, packet.getLength()).trim();
        if (message.isEmpty()) return;

        final String[] parts = message.split(SEP);
        if (parts.length == 0) return;

        final InetAddress ip = packet.getAddress();
        final int port = packet.getPort();

        int index = findClientIndex(ip, port);

        System.out.println("[SERVER] Recibido: " + message + " desde " + ip + ":" + port);

        // CONNECT
        if (CMD_CONNECT.equals(parts[0])) {

            if (index != -1) {
                sendMessage(MSG_ALREADY_CONNECTED, ip, port);
                return;
            }

            if (connectedClients >= MAX_CLIENTS) {
                sendMessage(MSG_FULL, ip, port);
                return;
            }

            connectedClients++;
            Client newClient = new Client(connectedClients, ip, port);
            clients.add(newClient);

            sendMessage("Connected:" + connectedClients, ip, port);

            // cuando están los 2 y todavía no arrancó, arranca
            if (connectedClients == MAX_CLIENTS && !partidaArrancada) {
                partidaArrancada = true;

                seedPartida = System.currentTimeMillis();
                nivelPartida = 1;

                sendMessageToAll("Start:" + seedPartida + ":" + nivelPartida);
                System.out.println("[SERVER] Start enviado seed=" + seedPartida + " nivel=" + nivelPartida);

                gameController.configure(seedPartida, nivelPartida);
                gameController.startGame();
            }

            return;
        }

        // no connect => debe estar conectado
        if (index == -1) {
            sendMessage(MSG_NOT_CONNECTED, ip, port);
            return;
        }

        Client client = clients.get(index);

        switch (parts[0]) {

            case CMD_MOVE: {
                // Move:dx:dy
                if (parts.length >= 3) {
                    Integer dx = tryParseInt(parts[1]);
                    Integer dy = tryParseInt(parts[2]);
                    if (dx != null && dy != null) {
                        gameController.move(client.getNum(), dx, dy);
                    } else {
                        System.out.println("[SERVER] Move mal formado: " + message);
                    }
                }
                break;
            }

            case CMD_SPAWN: {
                // Spawn:id:px:py
                if (parts.length >= 4) {
                    Integer id = tryParseInt(parts[1]);
                    Float px = tryParseFloat(parts[2]);
                    Float py = tryParseFloat(parts[3]);
                    if (id != null && px != null && py != null) {
                        gameController.spawn(id, px, py);
                    } else {
                        System.out.println("[SERVER] Spawn mal formado: " + message);
                    }
                }
                break;
            }

            case CMD_DOOR: {
                // ✅ soporta ambos formatos:
                // Viejo: Door:ORIGEN:DESTINO:DIR  (len 4)
                // Nuevo: Door:PLAYER:ORIGEN:DESTINO:DIR (len 5)

                int playerNum = client.getNum();
                String origen;
                String destino;
                String dir;

                if (parts.length >= 5) {
                    Integer parsedPlayer = tryParseInt(parts[1]);
                    if (parsedPlayer == null) {
                        System.out.println("[SERVER] Door mal formado (player): " + message);
                        break;
                    }
                    playerNum = parsedPlayer;
                    origen = parts[2];
                    destino = parts[3];
                    dir = parts[4];

                } else if (parts.length >= 4) {
                    origen = parts[1];
                    destino = parts[2];
                    dir = parts[3];

                } else {
                    System.out.println("[SERVER] Door mal formado: " + message);
                    break;
                }

                System.out.println("[SERVER] Door OK -> P" + playerNum + " " + origen + " -> " + destino + " (" + dir + ")");
                // ✅ Puertas ahora son autoritativas por contacto en el server.
                // Ignoramos mensajes Door desde cliente para evitar desync/cheat.
                System.out.println("[SERVER] Ignorando Door desde cliente: " + message);
                break;
            }

            default:
                // ignorar desconocidos
                break;
        }
    }

    private int findClientIndex(InetAddress ip, int port) {
        String id = Client.buildId(ip, port);
        for (int i = 0; i < clients.size(); i++) {
            if (id.equals(clients.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }


    public void sendMessage(String message, InetAddress clientIp, int clientPort) {
        if (socket == null || socket.isClosed()) return;

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

        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) s.close();

        interrupt();
    }

    // ===== Helpers parse =====

    private static Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private static Float tryParseFloat(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return null; }
    }
}
