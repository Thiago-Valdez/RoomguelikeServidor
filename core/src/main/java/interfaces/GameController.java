package interfaces;

public interface GameController {

    /** Arranca la lógica del server (1 sola vez). */
    void startGame();

    /** Input recibido desde un cliente (dx, dy). */
    void move(int playerNum, int dx, int dy);

    void spawn(int playerNum, float x, float y);

    void door(int playerNum, String origen, String destino, String dir);


}
