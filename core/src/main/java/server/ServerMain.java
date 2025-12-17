package server;

import interfaces.GameController;

public class ServerMain {
    public static void main(String[] args) {

        GameControllerImpl game = new GameControllerImpl();
        ServerThread server = new ServerThread(game);

        // 🔥 conexión cruzada
        game.setServer(server);

        server.start();
    }
}
