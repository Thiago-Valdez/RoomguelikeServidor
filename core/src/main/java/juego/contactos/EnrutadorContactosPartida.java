package juego.contactos;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;

import entidades.Entidad;
import entidades.GestorDeEntidades;
import entidades.datos.*;
import entidades.enemigos.*;
import entidades.items.*;
import entidades.personajes.*;
import entidades.sprites.*;
import juego.Partida;
import juego.eventos.EventoPuerta;
import juego.eventos.EventoPickup;
import juego.eventos.EventoFinNivel;
import juego.eventos.EventoBoton;
import mapa.botones.*;
import mapa.generacion.*;
import mapa.trampilla.DatosTrampilla;
import mapa.model.*;
import mapa.puertas.*;

/**
 * ContactListener dedicado al gameplay.
 *
 * Importante: NO modificamos Box2D dentro del callback.
 * Solo encolamos eventos y el update de Partida los procesa.
 */
public final class EnrutadorContactosPartida implements ContactListener {

    private final Partida partida;

    public EnrutadorContactosPartida(Partida partida) {
        this.partida = partida;
    }

    @Override
    public void beginContact(Contact contact) {
        Fixture a = contact.getFixtureA();
        Fixture b = contact.getFixtureB();

        // Puertas
        encolarContactoPuerta(a, b);
        encolarContactoPuerta(b, a);

        // Pickups
        encolarPickup(a, b);
        encolarPickup(b, a);

        // Botones (DOWN)
        encolarBoton(a, b, true);
        encolarBoton(b, a, true);

        // Trampilla (fin de nivel)
        encolarFinNivel(a, b);
        encolarFinNivel(b, a);

        detectarDanioJugadorEnemigo(a, b);
    }

    @Override
    public void endContact(Contact contact) {
        Fixture a = contact.getFixtureA();
        Fixture b = contact.getFixtureB();

        // Botones (UP)
        encolarBoton(a, b, false);
        encolarBoton(b, a, false);
    }

    @Override public void preSolve(Contact contact, Manifold oldManifold) {}
    @Override public void postSolve(Contact contact, ContactImpulse impulse) {}

    private void encolarContactoPuerta(Fixture puertaFx, Fixture otroFx) {
        if (puertaFx == null || otroFx == null) return;
        if (partida.getSistemaTransicionSala().bloqueoActivo()) return;

        Object ud = puertaFx.getUserData();
        if (!(ud instanceof DatosPuerta puerta)) return;

        int jugadorId = getJugadorId(otroFx);
        if (jugadorId == -1) return;

        Habitacion salaActual = partida.getSalaActual();
        if (salaActual == puerta.origen() || salaActual == puerta.destino()) {
            partida.getEventos().publicar(new EventoPuerta(puerta, jugadorId));
        }
    }

    private void encolarPickup(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;
        int jugadorId = getJugadorId(jugadorFx);
        if (jugadorId == -1) return;

        Object ud = otroFx.getUserData();
        if (ud instanceof entidades.items.Item item) {
            partida.getEventos().publicar(new EventoPickup(item, jugadorId));
        }
    }

    private void encolarBoton(Fixture jugadorFx, Fixture otroFx, boolean down) {
        if (jugadorFx == null || otroFx == null) return;
        int jugadorId = getJugadorId(jugadorFx);
        if (jugadorId == -1) return;

        Object ud = otroFx.getUserData();
        if (ud instanceof DatosBoton db) {
            partida.getEventos().publicar(new EventoBoton(db, jugadorId, down));
        }
    }

    private void encolarFinNivel(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;
        int jugadorId = getJugadorId(jugadorFx);
        if (jugadorId == -1) return;

        Object ud = otroFx.getUserData();
        if (ud instanceof DatosTrampilla dt) {
            // Solo fin de nivel si la trampilla pertenece a la sala actual
            if (dt.sala() == partida.getSalaActual()) {
                partida.getEventos().publicar(new EventoFinNivel(dt.sala()));
            }
        }
    }

    private int getJugadorId(Fixture fx) {
        if (fx == null) return -1;
        Body b = fx.getBody();
        if (b == null) return -1;
        Object ud = b.getUserData();
        if (ud instanceof Jugador j) return j.getId();
        return -1;
    }

    private void detectarDanioJugadorEnemigo(Fixture a, Fixture b) {
        if (a == null || b == null) return;

        Object ua = a.getBody() != null ? a.getBody().getUserData() : null;
        Object ub = b.getBody() != null ? b.getBody().getUserData() : null;

        if (ua instanceof Jugador j && ub instanceof Enemigo e) {
            Vector2 pe = e.getCuerpoFisico().getPosition();
            partida.encolarDanioJugador(j.getId(), pe.x, pe.y);
            return;
        }
        if (ua instanceof Enemigo e && ub instanceof Jugador j) {
            Vector2 pe = e.getCuerpoFisico().getPosition();
            partida.encolarDanioJugador(j.getId(), pe.x, pe.y);
        }
    }
}