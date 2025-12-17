package juego.sistemas;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.physics.box2d.Body;

import control.puzzle.ControlPuzzlePorSala;
import entidades.GestorDeEntidades;
import juego.eventos.ColaEventos;
import juego.eventos.EventoDanio;
import juego.eventos.EventoPickup;
import entidades.personajes.Jugador;
import entidades.sprites.SpritesEntidad;
import mapa.botones.DatosBoton;
import juego.eventos.EventoBoton;
import mapa.model.Habitacion;

/**
 * Procesa colas de eventos (pickup, botones, daño) para evitar modificar Box2D dentro de callbacks
 * y mantener la lógica del frame centralizada.
 */
public final class ProcesadorColasEventos {

    public void procesarItemsPendientes(
            ColaEventos eventos,
            Set<entidades.items.Item> itemsYaProcesados,
            GestorDeEntidades gestorEntidades
    ) {
        if (eventos == null || eventos.isEmpty()) return;

        itemsYaProcesados.clear();

        eventos.drenar(EventoPickup.class, ev -> {
            if (!itemsYaProcesados.add(ev.item())) return;
            gestorEntidades.recogerItem(ev.jugadorId(), ev.item());
        });
    }

    public void procesarBotonesPendientes(
        ColaEventos eventos,
        Habitacion salaActual,
        ControlPuzzlePorSala controlPuzzle,
        Consumer<Habitacion> matarEnemigosDeSalaConAnim,
        List<mapa.botones.BotonVisual> botonesVisuales
    ) {
        if (eventos == null || eventos.isEmpty()) return;

        if (controlPuzzle == null) {
            // Si todavía no hay puzzle, descartamos eventos de botones.
            eventos.limpiar(EventoBoton.class);
            return;
        }

        eventos.drenar(EventoBoton.class, ev -> {
            DatosBoton boton = ev.boton();
            int jugadorId = ev.jugadorId();

            if (boton.sala() != salaActual) return;

            boolean valido = (jugadorId == boton.jugadorId());
            if (!valido) return;

            // ✅ VISUAL: marcar DOWN/UP para TODOS los botones de esa sala + jugador
            if (botonesVisuales != null) {
                boolean down = ev.down();
                for (mapa.botones.BotonVisual bv : botonesVisuales) {
                    if (bv == null) continue;
                    if (bv.sala != salaActual) continue;
                    if (bv.jugadorId != jugadorId) continue;
                    bv.presionado = down;
                }
            }

            // ✅ LÓGICA PUZZLE
            if (ev.down()) {
                boolean desbloqueo = controlPuzzle.botonDown(salaActual, boton.jugadorId());
                if (desbloqueo) {
                    Gdx.app.log("PUZZLE", "Sala desbloqueada: " + salaActual.nombreVisible);

                    if (matarEnemigosDeSalaConAnim != null) {
                        matarEnemigosDeSalaConAnim.accept(salaActual);
                    }
                }

            } else {
                controlPuzzle.botonUp(salaActual, boton.jugadorId());
            }
        });
    }


    public void procesarDaniosPendientes(
            ColaEventos eventos,
            Set<Integer> jugadoresDanioFrame,
            GestorDeEntidades gestorEntidades,
            SistemaSpritesEntidades sprites
    ) {
        if (eventos == null || eventos.isEmpty()) return;

        jugadoresDanioFrame.clear();

        eventos.drenar(EventoDanio.class, ev -> {
            int id = ev.jugadorId();
            if (!jugadoresDanioFrame.add(id)) return; // evita daño duplicado en el mismo frame

            Jugador j = gestorEntidades.getJugador(id);
            if (j == null) return;

            // respetar inmune / enMuerte / muerto
            if (!j.estaViva() || j.estaEnMuerte() || j.esInmune()) return;

            Body body = j.getCuerpoFisico();
            if (body == null) return;

            // =========================
            // 1) Separación anti-loop (antes de congelar)
            // =========================
            float px = body.getPosition().x;
            float py = body.getPosition().y;

            float dx = px - ev.ex();
            float dy = py - ev.ey();

            float len2 = dx * dx + dy * dy;
            if (len2 < 0.0001f) {
                dx = 1f;
                dy = 0f;
                len2 = 1f;
            }

            float invLen = (float)(1.0 / Math.sqrt(len2));
            dx *= invLen;
            dy *= invLen;

            float separacion = 40f; // px
            body.setTransform(px + dx * separacion, py + dy * separacion, body.getAngle());
            body.setLinearVelocity(0f, 0f);
            body.setAngularVelocity(0f);

            // =========================
            // 2) Aplicar daño + cooldown anti re-hit
            // =========================
            j.recibirDanio();
            j.marcarHitCooldown(1.0f);

            // =========================
            // 3) Animación + feedback
            // =========================
            SpritesEntidad sp = (sprites != null) ? sprites.get(j) : null;
            if (sp != null) {
                sp.iniciarMuerte();
            }
        });
    }
}
