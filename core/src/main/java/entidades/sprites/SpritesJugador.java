package entidades.sprites;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import entidades.datos.Genero;
import entidades.personajes.Jugador;

public class SpritesJugador extends SpritesEntidad {

    private final Jugador jugador;

    public SpritesJugador(Jugador jugador, int frameW, int frameH) {
        super(jugador, frameW, frameH);
        this.jugador = jugador;

        cargar();
        construirAnimaciones();
    }

    @Override
    protected String pathQuieto() {
        String base = (jugador.getGenero() == Genero.FEMENINO) ? "jugador_fem" : "jugador_masc";
        return "Jugadores/" + base + "_quieto.png";
    }

    @Override
    protected String pathMovimiento() {
        String base = (jugador.getGenero() == Genero.FEMENINO) ? "jugador_fem" : "jugador_masc";
        return "Jugadores/" + base + "_movimiento.png";
    }

    @Override
    protected String pathMuerte() {
        String base = (jugador.getGenero() == Genero.FEMENINO) ? "jugador_fem" : "jugador_masc";
        return "Jugadores/" + base + "_muerte.png";
    }

    @Override
    public void render(SpriteBatch batch) {
        if (entidad == null || entidad.getCuerpoFisico() == null) return;

        float vx = entidad.getCuerpoFisico().getLinearVelocity().x;
        if (Math.abs(vx) > 0.001f) {
            ultimaMiradaDerecha = vx >= 0f;
        }

        TextureRegion frame = elegirFrame();

        float w = frame.getRegionWidth();
        float h = frame.getRegionHeight();

        // Y: mantenemos tu ancla al pie (no tocar)
        float y = entidad.getPosicion().y - anclaPie + offsetY;

        // X base: centrado al cuerpo
        float baseX = entidad.getPosicion().x - w / 2f;

        // ✅ clave: el offsetX debe espejarse cuando mirás a la izquierda
        float ox = ultimaMiradaDerecha ? offsetX : -offsetX;

        if (ultimaMiradaDerecha) {
            batch.draw(frame, baseX + ox, y, w, h);
        } else {
            // dibujamos espejo, pero con el offset ya corregido
            batch.draw(frame, baseX + ox + w, y, -w, h);
        }
    }


    @Override
    public void update(float delta) {
        super.update(delta);

        // ✅ Sync fuerte: el estado del sprite depende del estado real del jugador
        if (jugador.estaEnMuerte()) {
            iniciarMuerte(); // se mantiene en muerte mientras el jugador esté en stun
        } else {
            if (estaEnMuerte() || muerteTerminada()) {
                detenerMuerte(); // al levantarse vuelve a idle/move
            }
        }
    }
}
