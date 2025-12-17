package pantallas;

import com.badlogic.gdx.Screen;

import io.github.principal.Principal;
import juego.Partida;

/**
 * Pantalla fina: delega toda la lógica del gameplay a {@link Partida}.
 * La idea es que este Screen no crezca: init/render/dispose quedan centralizados.
 */
public class JuegoPrincipal implements Screen {

    private final Principal game;
    private Partida partida;

    public JuegoPrincipal(Principal game) {
        this.game = game;
    }

    @Override
    public void show() {
        game.audio.playJuego();
        partida = new Partida(game);
        partida.start();
    }

    @Override
    public void render(float delta) {
        partida.render(delta);

        if (partida.consumirVictoriaSolicitada()) {
            game.cambiarPantalla(new PantallaGanaste(game));
            return;
        }

        if (partida.consumirGameOverSolicitado()) {
            game.cambiarPantalla(new PantallaGameOver(game));
        }
    }



    @Override
    public void resize(int width, int height) {
        if (partida != null) partida.resize(width, height);
    }

    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void hide() {

    }

    @Override
    public void dispose() {
        if (partida != null) {
            partida.dispose();
            partida = null;
        }
    }
}
