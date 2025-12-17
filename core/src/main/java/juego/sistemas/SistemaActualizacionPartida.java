package juego.sistemas;

import java.util.List;
import java.util.Set;

import camara.CamaraDeSala;
import control.input.ControlJugador;
import control.puzzle.ControlPuzzlePorSala;
import control.salas.GestorSalas;
import entidades.GestorDeEntidades;
import juego.eventos.ColaEventos;
import juego.eventos.EventoPuerta;
import entidades.items.Item;
import entidades.personajes.Jugador;
import fisica.FisicaMundo;
import mapa.generacion.DisposicionMapa;
import mapa.model.Habitacion;

/**
 * Agrupa el update del gameplay (sin render).
 * Mantiene a Partida como un orquestador chico y legible.
 */
public final class SistemaActualizacionPartida {

    private final GestorDeEntidades gestorEntidades;
    private final FisicaMundo fisica;
    private final CamaraDeSala camaraSala;
    private final SistemaTransicionSala transicionSala;
    private final ProcesadorColasEventos procesadorEventos;
    private final SistemaSpritesEntidades sprites;

    public SistemaActualizacionPartida(
            GestorDeEntidades gestorEntidades,
            FisicaMundo fisica,
            CamaraDeSala camaraSala,
            SistemaTransicionSala transicionSala,
            ProcesadorColasEventos procesadorEventos,
            SistemaSpritesEntidades sprites
    ) {
        this.gestorEntidades = gestorEntidades;
        this.fisica = fisica;
        this.camaraSala = camaraSala;
        this.transicionSala = transicionSala;
        this.procesadorEventos = procesadorEventos;
        this.sprites = sprites;
    }

    public Habitacion actualizar(ContextoActualizacionPartida ctx) {
        if (ctx == null) return null;

        float delta = ctx.delta;
        Habitacion salaActual = ctx.salaActual;

        Jugador jugador1 = ctx.jugador1;
        Jugador jugador2 = ctx.jugador2;
        ControlJugador controlJugador1 = ctx.controlJugador1;
        ControlJugador controlJugador2 = ctx.controlJugador2;

        // cola unificada
        ColaEventos eventos = ctx.eventos;

        // helpers (dedupe)
        Set<Item> itemsYaProcesados = ctx.itemsYaProcesados;
        Set<Integer> jugadoresDanioFrame = ctx.jugadoresDanioFrame;

        List<mapa.botones.BotonVisual> botonesVisuales = ctx.botonesVisuales;

        ControlPuzzlePorSala controlPuzzle = ctx.controlPuzzle;
        GestorSalas gestorSalas = ctx.gestorSalas;
        DisposicionMapa disposicion = ctx.disposicion;
        java.util.function.BiConsumer<Habitacion, Habitacion> notificarCambioSala = ctx.notificarCambioSala;
        com.badlogic.gdx.maps.tiled.TiledMap mapaTiled = ctx.mapaTiled;
        com.badlogic.gdx.physics.box2d.World world = ctx.world;

        if (gestorEntidades == null || fisica == null) return salaActual;

        // 1) actualizar entidades (sin físicas)
        gestorEntidades.actualizar(delta, salaActual);

        // 2) input
        actualizarControles(delta, controlJugador1, controlJugador2);

        // 3) IA / enemigos
        gestorEntidades.actualizarEnemigos(delta, jugador1, jugador2);

        // 4) físicas
        fisica.step(delta);

        // 5) transición y colas
        if (transicionSala != null) {
            salaActual = transicionSala.procesarPuertasPendientes(
                    salaActual,
                    eventos,
                    controlPuzzle,
                    gestorSalas,
                    disposicion,
                    notificarCambioSala,
                    mapaTiled,
                    world,
                    gestorEntidades,
                    sprites
            );
            transicionSala.tickCooldown();
        } else {
            if (eventos != null) eventos.limpiar(EventoPuerta.class);
        }

        if (procesadorEventos != null) {
            procesadorEventos.procesarItemsPendientes(eventos, itemsYaProcesados, gestorEntidades);

            java.util.function.Consumer<Habitacion> matarEnemigosDeSalaConAnim =
                    (sprites != null) ? sprites::matarEnemigosDeSalaConAnim : null;

            procesadorEventos.procesarBotonesPendientes(
                    eventos,
                    salaActual,
                    controlPuzzle,
                    matarEnemigosDeSalaConAnim,
                    botonesVisuales
            );

            procesadorEventos.procesarDaniosPendientes(eventos, jugadoresDanioFrame, gestorEntidades, sprites);
        }

        // 6) jugadores
        actualizarJugadores(delta, jugador1, jugador2);

        // 7) housekeeping sprites
        if (sprites != null) {
            sprites.registrarSpritesDeEnemigosVivos();
            sprites.procesarEnemigosEnMuerte();
            sprites.limpiarSpritesDeEntidadesMuertas();
        }

        // 8) cámara (en update, no en render)
        if (camaraSala != null) camaraSala.update(delta);

        return salaActual;
    }

    private void actualizarControles(float delta, ControlJugador c1, ControlJugador c2) {
        if (c1 != null) c1.actualizar(delta);
        if (c2 != null) c2.actualizar(delta);
    }

    private void actualizarJugadores(float delta, Jugador j1, Jugador j2) {
        if (j1 != null) {
            boolean estabaEnMuerte = j1.estaEnMuerte();
            j1.updateEstado(delta);
            j1.tick(delta);
            if (estabaEnMuerte && !j1.estaEnMuerte() && sprites != null) sprites.detenerMuerte(j1);
        }
        if (j2 != null) {
            boolean estabaEnMuerte = j2.estaEnMuerte();
            j2.updateEstado(delta);
            j2.tick(delta);
            if (estabaEnMuerte && !j2.estaEnMuerte() && sprites != null) sprites.detenerMuerte(j2);
        }
    }
}