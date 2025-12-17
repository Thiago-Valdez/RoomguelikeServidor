package io.github.principal.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import server.ServerApp;

public class ServerMain {

    public static void main(String[] args) {

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("Roomguelike Server");
        cfg.setWindowedMode(320, 240);   // ventana mínima
        cfg.setResizable(false);
        //cfg.setVsync(false);
        cfg.setForegroundFPS(60);
        cfg.setIdleFPS(60);

        new Lwjgl3Application(new ServerApp(), cfg);
    }
}
