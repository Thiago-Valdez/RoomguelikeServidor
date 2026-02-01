package entidades.personajes;

import java.util.*;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import entidades.Entidad;
import entidades.datos.Estilo;
import entidades.datos.Genero;
import entidades.items.Item;

public class Jugador extends Entidad {

    private final int id;

    // Estética / apariencia
    private Genero genero;
    private Estilo estilo;

    // Stats específicos de jugador (vida sí queda acá porque dijiste que enemigos no tienen vida)
    private int vida;
    private int vidaMaxima;

    private float velocidadBase = 100f;

    private boolean puedeMoverse = true;
    private float cooldownDanio = 0f;

    // Inventario simple (ítems pasivos)
    private final List<Item> objetos = new ArrayList<>();

    public Jugador(int id, String nombre,
                   Genero generoInicial,
                   Estilo estiloInicial) {

        super(nombre, 100f, null);

        this.id = id;
        this.genero = (generoInicial != null) ? generoInicial : Genero.MASCULINO;
        this.estilo = (estiloInicial != null) ? estiloInicial : Estilo.CLASICO;

        this.vidaMaxima = 3;
        this.vida = 3;

        // velocidad ya quedó seteada en super(nombre, 200f, null)
    }

    // ------------------ ID ------------------

    public int getId() {
        return id;
    }

    // ------------------ Estética ------------------

    public Genero getGenero() {
        return genero;
    }

    public void setGenero(Genero genero) {
        if (genero != null) {
            this.genero = genero;
        }
    }

    public Estilo getEstilo() {
        return estilo;
    }

    public void setEstilo(Estilo estilo) {
        if (estilo != null) {
            this.estilo = estilo;
        }
    }

    public String getClaveSpriteBase() {
        return "player_" + genero.getSufijoSprite() + "_" + estilo.getSufijoSprite();
    }

    // ------------------ Vida (solo jugador) ------------------

    public int getVida() {
        return vida;
    }

    public void setVida(int vida) {
        if (vida < 0) vida = 0;
        if (vida > vidaMaxima) vida = vidaMaxima;
        this.vida = vida;
    }

    public int getVidaMaxima() {
        return vidaMaxima;
    }

    public void setVidaMaxima(int vidaMaxima) {
        if (vidaMaxima < 1) vidaMaxima = 1;
        this.vidaMaxima = vidaMaxima;
        if (vida > vidaMaxima) {
            vida = vidaMaxima;
        }
    }

    // ------------------ Física ------------------
    // OJO: Entidad ya tiene getCuerpoFisico()/setCuerpoFisico().
    // Pero necesitamos mantener tu comportamiento de setUserData(this) como "fuente de verdad".
    @Override
    public void setCuerpoFisico(Body cuerpoFisico) {
        super.setCuerpoFisico(cuerpoFisico);
        if (this.cuerpoFisico != null) {
            this.cuerpoFisico.setUserData(this); // ✅ fuente de verdad
        }
    }

    public boolean puedeMoverse() {
        return puedeMoverse && viva;
    }


    public void recibirDanio() {
        if (!viva || enMuerte || inmune) return;

        vida--;
        enMuerte = true;
        puedeMoverse = false;
        tiempoMuerte = 0f;

        // 🔒 congelar física
        if (cuerpoFisico != null) {
            cuerpoFisico.setLinearVelocity(0f, 0f);
            cuerpoFisico.setAngularVelocity(0f);
            cuerpoFisico.setType(BodyDef.BodyType.KinematicBody);
        }

        if (vida <= 0) {
            viva = false;
        }
    }

    public void updateEstado(float delta) {
        if (enMuerte) {
            tiempoMuerte += delta;

            if (tiempoMuerte >= 3f) {
                enMuerte = false;
                puedeMoverse = true;
                inmune = true;
                tiempoInmunidad = 0f;
            }

            // 🔓 volver a dinámico
            if (cuerpoFisico != null) {
                cuerpoFisico.setType(BodyDef.BodyType.DynamicBody);
            }
        }

        if (inmune) {
            tiempoInmunidad += delta;
            if (tiempoInmunidad >= 2f) { // inmunidad post-daño
                inmune = false;
            }
        }
    }

    public boolean puedeRecibirDanio() {
        return estaViva() && !enMuerte && !inmune && cooldownDanio <= 0f;
    }

    public void tick(float delta) {
        if (cooldownDanio > 0f) cooldownDanio -= delta;
        updateEstado(delta);
    }

    @Override
    public float getVelocidad() {
        // La velocidad efectiva del jugador vive en Entidad.velocidad.
        // (Los items llaman jugador.getVelocidad()/setVelocidad(), así que tiene que ser consistente.)
        return super.getVelocidad();
    }

    @Override
    public void setVelocidad(float velocidad) {
        super.setVelocidad(velocidad);
    }

    public void marcarHitCooldown(float segundos) {
        cooldownDanio = Math.max(cooldownDanio, segundos);
    }

    // ------------------ Inventario ------------------

    public List<Item> getObjetos() {
        return Collections.unmodifiableList(objetos);
    }

    public void agregarObjeto(Item item) {
        if (item == null) return;
        objetos.add(item);
        reaplicarEfectosDeItems();   // ✅ garantiza que el stat quede aplicado
    }


    public void removerObjeto(Item item) {
        if (objetos.remove(item)) {
            reaplicarEfectosDeItems();
        }
    }


    public void reaplicarEfectosDeItems() {
        // reset a base
        this.vidaMaxima = 3;
        this.velocidadBase = 100f;
        // Importante: el gameplay usa Entidad.velocidad (via getVelocidad),
        // así que reseteamos ahí.
        super.setVelocidad(velocidadBase);

        if (vida > vidaMaxima) vida = vidaMaxima;

        for (Item item : objetos) {
            item.aplicarModificacion(this);
        }
    }

}
