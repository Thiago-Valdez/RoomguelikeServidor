package juego.sistemas;

import java.util.function.BiConsumer;

import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.physics.box2d.World;

import control.puzzle.ControlPuzzlePorSala;
import control.salas.GestorSalas;
import entidades.GestorDeEntidades;
import entidades.enemigos.EnemigosDesdeTiled;
import juego.eventos.ColaEventos;
import mapa.generacion.DisposicionMapa;
import mapa.model.Habitacion;
import juego.eventos.EventoPuerta;

/**
 * Maneja el cambio de sala por puertas y el cooldown anti "ping-pong".
 * Mantiene el comportamiento existente y deja a Partida como orquestador.
 */
public final class SistemaTransicionSala {

    private int framesBloqueoPuertas = 0;

    public boolean bloqueoActivo() {
        return framesBloqueoPuertas > 0;
    }

    public void tickCooldown() {
        if (framesBloqueoPuertas > 0) framesBloqueoPuertas--;
    }

    /**
     * Procesa el primer evento de puerta pendiente (si existe), aplicando cooldown.
     * @return salaActual actualizada (o la misma si no hubo transición)
     */
    public Habitacion procesarPuertasPendientes(
            Habitacion salaActual,
            ColaEventos eventos,
            ControlPuzzlePorSala controlPuzzle,
            GestorSalas gestorSalas,
            DisposicionMapa disposicion,
            BiConsumer<Habitacion, Habitacion> notificarCambioSala,
            TiledMap mapaTiled,
            World world,
            GestorDeEntidades gestorEntidades,
            SistemaSpritesEntidades sprites
    ) {

        // puertas cerradas por puzzle/combat/etc
        if (eventos == null || eventos.isEmpty()) return salaActual;

        // puertas cerradas por puzzle/combat/etc
        if (controlPuzzle != null && controlPuzzle.estaBloqueada(salaActual)) {
            eventos.limpiar(EventoPuerta.class);
            return salaActual;
        }

        if (framesBloqueoPuertas > 0) {
            eventos.limpiar(EventoPuerta.class);
            return salaActual;
        }

        EventoPuerta ev = eventos.pollFirst(EventoPuerta.class);
        if (ev == null) return salaActual;
        Habitacion nueva = gestorSalas.irASalaVecinaPorPuerta(salaActual, ev.puerta(), ev.jugadorId());

        if (nueva != null) {
            Habitacion anterior = salaActual;

            // cambio de sala: limpiamos instantáneo (no anim) para evitar cuerpos vivos fuera de la sala
            gestorEntidades.eliminarEnemigosDeSala(anterior);

            // limpiamos tracking de muerte de esa sala
            if (sprites != null) sprites.limpiarSpritesDeEntidadesMuertas();

            salaActual = nueva;
            disposicion.descubrir(salaActual);

            if (notificarCambioSala != null) {
                notificarCambioSala.accept(anterior, salaActual);
            }

            if (controlPuzzle != null) controlPuzzle.alEntrarASala(salaActual);

            EnemigosDesdeTiled.crearEnemigosDesdeMapa(mapaTiled, salaActual, world, gestorEntidades);

            if (controlPuzzle != null && gestorEntidades != null) {
                controlPuzzle.setEnemigosVivos(salaActual, gestorEntidades.getEnemigosDeSala(salaActual).size());
            }
            if (sprites != null) {
                sprites.registrarSpritesDeEnemigosVivos();
                sprites.limpiarSpritesDeEntidadesMuertas();
            }
        }

        framesBloqueoPuertas = 15;
        // descartar cualquier otro evento de puerta en el mismo frame
        eventos.limpiar(EventoPuerta.class);
        return salaActual;
    }
}
