package server;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;

public class ServerApp extends ApplicationAdapter {

    private ServerThread server;
    private GameControllerImpl game;

    @Override
    public void create() {
        System.out.println("[SERVER] Backend inicializado");

        // ✅ Cargar TMX acá (hilo GL)
        TiledMap map = new TmxMapLoader().load("tmx/mapa.tmx"); // <-- tu TMX real

        game = new GameControllerImpl();
        game.setTiledMap(map); // <-- nuevo setter

        server = new ServerThread(game);
        game.setServer(server);

        server.start();
    }

    @Override
    public void dispose() {
        if (server != null) server.terminate();
        if (game != null) game.stop();
    }
}
