package entidades.enemigos;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import entidades.Entidad;
import entidades.personajes.Jugador;

/**
 * Enemigo básico.
 * - No tiene vida (se elimina al resolver puzzle de sala).
 * - Persigue al jugador objetivo (1/2) o al más cercano (0).
 */
public class Enemigo extends Entidad {

    /**
     * 0 = el más cercano
     * 1 = jugador 1
     * 2 = jugador 2
     */
    private int jugadorObjetivo;

    // Parámetros simples para evitar vibración / “pegarse” al jugador
    private float distanciaMinima = 0.35f; // en unidades del mundo Box2D (ajustable)

    public Enemigo(String nombre, float velocidad, Body cuerpoFisico, int jugadorObjetivo) {
        super(nombre, velocidad, cuerpoFisico);
        this.jugadorObjetivo = jugadorObjetivo;
        if (this.cuerpoFisico != null) {
            this.cuerpoFisico.setUserData(this);
        }
    }

    public int getJugadorObjetivo() {
        return jugadorObjetivo;
    }

    public void setJugadorObjetivo(int jugadorObjetivo) {
        this.jugadorObjetivo = jugadorObjetivo;
    }

    public float getDistanciaMinima() {
        return distanciaMinima;
    }

    public void setDistanciaMinima(float distanciaMinima) {
        if (distanciaMinima < 0f) distanciaMinima = 0f;
        this.distanciaMinima = distanciaMinima;
    }

    /**
     * Update simple: si hay target, lo sigue.
     * Le pasamos los jugadores desde afuera (Partida / sistema de IA),
     * así el Enemigo no conoce gestores globales.
     */
    public void actualizar(float delta, Jugador jugador1, Jugador jugador2) {
        Jugador objetivo = elegirObjetivo(jugador1, jugador2);
        if (objetivo == null) {
            detener();
            return;
        }

        Vector2 posObj = objetivo.getPosicion();
        Vector2 miPos = getPosicion();

        // Si está muy cerca, frena para evitar vibración o “empuje constante”
        if (miPos.dst2(posObj) <= distanciaMinima * distanciaMinima) {
            detener();
            return;
        }

        moverHacia(posObj);
    }

    // Mantenemos el hook default por compatibilidad (por si alguien llama actualizar(delta) sin jugadores)
    @Override
    public void actualizar(float delta) {
        // No hace nada: para que el update “real” tenga contexto de jugadores.
        // (A futuro esto se puede resolver con un AiController que tenga referencias al mundo.)
    }

    private Jugador elegirObjetivo(Jugador j1, Jugador j2) {
        if (jugadorObjetivo == 1) return j1;
        if (jugadorObjetivo == 2) return j2;

        // 0 o cualquier otro valor: el más cercano válido
        if (j1 == null && j2 == null) return null;
        if (j1 == null) return j2;
        if (j2 == null) return j1;

        Vector2 miPos = getPosicion();
        float d1 = miPos.dst2(j1.getPosicion());
        float d2 = miPos.dst2(j2.getPosicion());
        return (d1 <= d2) ? j1 : j2;
    }
}
