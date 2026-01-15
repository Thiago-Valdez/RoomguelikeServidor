package server;

import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;

import java.util.*;
import java.util.function.Consumer;

import fisica.ColisionesDesdeTiled;
import fisica.FisicaMundo;
import interfaces.GameController;
import juego.inicializacion.InicializadorSensoresPuertas;
import mapa.generacion.*;
import mapa.model.*;
import mapa.puertas.DatosPuerta;

public class GameControllerImpl implements GameController {

    private static final float DT = 1f / 60f;
    private static final float MOVE_SPEED = 160f;
    private static final int NET_HZ = 20; // ✅ 20 updates/s (liviano y suficiente)
    private ServerThread server;

    private World world;
    private Body b1, b2;

    // índices 1..2 usados
    private final int[] dx = new int[3];
    private final int[] dy = new int[3];

    private volatile boolean running = false;

    // ✅ Red: snapshots a tasa fija
    private volatile long nextNetSendNs = 0L;

    // ✅ map pre-cargado en hilo GL
    private TiledMap map;

    // =====================
    // Config de partida (para puertas autoritativas)
    // =====================
    private long seedPartida = 0L;
    private int nivelPartida = 1;

    private DisposicionMapa disposicion;
    private Habitacion salaActual = Habitacion.INICIO_1;

    // Anti-retrigger de puertas (el contacto puede disparar varias veces)
    private long lastDoorNs = 0L;
    private static final long DOOR_COOLDOWN_NS = 400_000_000L; // 400ms

    // ✅ temp para evitar alloc por frame
    private final Vector2 tmpVel = new Vector2();

    public void setServer(ServerThread server) {
        this.server = server;
    }

    public void setTiledMap(TiledMap map) {
        this.map = map;
    }

        @Override
    public void configure(long seed, int nivel) {
        this.seedPartida = seed;
        this.nivelPartida = Math.max(1, nivel);
    }

@Override
    public void startGame() {
        System.out.println("[SERVER] Juego iniciado");

        initFisicaServidor();

        enviarPosiciones(true);
        startLoop();
    }

    private void initFisicaServidor() {
        if (map == null) {
            throw new IllegalStateException("TiledMap no seteado. Llamá setTiledMap(map) antes de startGame().");
        }

        if (world != null) {
            world.dispose();
            world = null;
        }

        world = new World(new Vector2(0f, 0f), true);

        // ✅ colisiones desde Tiled
        ColisionesDesdeTiled.crearColisiones(map, world);

        b1 = crearJugadorBody(224f, 2304f);
        b2 = crearJugadorBody(288f, 2304f);

        b1.setBullet(true);
        b2.setBullet(true);

        b1.setLinearDamping(6f);
        b2.setLinearDamping(6f);


        // ✅ Identificar bodies para saber qué jugador tocó la puerta
        b1.setUserData(1);
        b2.setUserData(2);

        // ✅ Generar disposición + sensores de puertas en el SERVER (autoritativo)
        GeneradorMapa.Configuracion cfg = new GeneradorMapa.Configuracion();
        cfg.nivel = Math.max(1, nivelPartida);
        cfg.semilla = seedPartida;

        List<Habitacion> todasLasHabitaciones = Arrays.asList(Habitacion.values());
        GrafoPuertas grafo = new GrafoPuertas(todasLasHabitaciones, new Random(cfg.semilla));

        GeneradorMapa generador = new GeneradorMapa(cfg, grafo);
        disposicion = generador.generar();
        salaActual = disposicion.salaInicio();

        // Creamos sensores de puertas para TODA la disposición (fixtures con userData = DatosPuerta)
        FisicaMundo fisica = new FisicaMundo(world);
        InicializadorSensoresPuertas.generarSensoresPuertas(
            fisica,
            disposicion,
            reg -> { /* no necesitamos visuales en server */ }
        );


        // ✅ Puertas autoritativas: el SERVER detecta el contacto y emite UpdateRoom
        world.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                Fixture a = contact.getFixtureA();
                Fixture b = contact.getFixtureB();
                tryDoor(a, b);
                tryDoor(b, a);
            }
            @Override public void endContact(Contact contact) {}
            @Override public void preSolve(Contact contact, Manifold oldManifold) {}
            @Override public void postSolve(Contact contact, ContactImpulse impulse) {}
        });
    }

    private Body crearJugadorBody(float px, float py) {
        BodyDef bd = new BodyDef();
        bd.type = BodyDef.BodyType.DynamicBody;
        bd.position.set(px, py);
        bd.fixedRotation = true;

        Body body = world.createBody(bd);

        CircleShape shape = new CircleShape();
        shape.setRadius(12f);

        FixtureDef fd = new FixtureDef();
        fd.shape = shape;
        fd.density = 1f;
        fd.friction = 0.2f;
        fd.restitution = 0f;

        body.createFixture(fd).setUserData("jugador");
        shape.dispose();

        return body;
    }

    @Override
    public void move(int playerNum, int dx, int dy) {
        if (playerNum < 1 || playerNum > 2) return;
        this.dx[playerNum] = dx;
        this.dy[playerNum] = dy;
    }

    @Override
    public void spawn(int playerNum, float px, float py) {
        Body b = (playerNum == 1) ? b1 : (playerNum == 2 ? b2 : null);
        if (b == null) return;

        b.setTransform(px, py, b.getAngle());
        b.setLinearVelocity(0f, 0f);
        enviarPosiciones(true);
    }

    @Override
    public void door(int playerNum, String origen, String destino, String dirStr) {
        if (server == null) return;

        server.sendMessageToAll("UpdateRoom:" + destino + ":" + dirStr + ":" + playerNum);

        mapa.model.Habitacion h = mapa.model.Habitacion.valueOf(destino);
        mapa.model.Direccion dir = mapa.model.Direccion.valueOf(dirStr);
        mapa.model.Direccion entrada = dir.opuesta();

        float cx = h.gridX * 512f + 256f;
        float cy = h.gridY * 512f + 256f;

        float off = 200f;
        switch (entrada) {
            case NORTE: cy += off; break;
            case SUR:   cy -= off; break;
            case ESTE:  cx += off; break;
            case OESTE: cx -= off; break;
        }

        float sep = 24f;

        if (b1 != null) { b1.setTransform(cx - sep, cy, b1.getAngle()); b1.setLinearVelocity(0,0); }
        if (b2 != null) { b2.setTransform(cx + sep, cy, b2.getAngle()); b2.setLinearVelocity(0,0); }

        enviarPosiciones(true);
    }


    private void tryDoor(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;

        // jugador fixture identificada por userData = "jugador"
        if (!"jugador".equals(jugadorFx.getUserData())) return;

        Object ud = otroFx.getUserData();
        if (!(ud instanceof DatosPuerta puerta)) return;

        // Solo vale si estoy en la sala ORIGEN de esa puerta
        if (puerta.origen() != salaActual) return;

        // cooldown (evita disparos múltiples por el mismo contacto)
        long now = System.nanoTime();
        if (now - lastDoorNs < DOOR_COOLDOWN_NS) return;
        lastDoorNs = now;

        Object bUd = jugadorFx.getBody() != null ? jugadorFx.getBody().getUserData() : null;
        int playerNum = (bUd instanceof Integer i) ? i : 1;

        // Ejecuta transición autoritativa (teleporta + UpdateRoom + snapshot)
        door(
            playerNum,
            puerta.origen().name(),
            puerta.destino().name(),
            puerta.direccion().name()
        );



        // En tu juego ambos jugadores viajan juntos (door() teleporta a ambos)
        salaActual = puerta.destino();
    }

    private void startLoop() {
        if (running) return;
        running = true;

        // ✅ Física a 60Hz en tiempo real + snapshots de red a tasa fija (NET_HZ).
        new Thread(() -> {
            final long stepNs = (long) (DT * 1_000_000_000L);
            final long netNs = 1_000_000_000L / NET_HZ;

            long nextStep = System.nanoTime();
            nextNetSendNs = nextStep; // primer snapshot inmediato

            while (running) {
                if (world == null) break;

                long now = System.nanoTime();

                // Dormimos hasta el próximo step de física si vamos antes.
                if (now < nextStep) {
                    long sleepNs = nextStep - now;
                    try {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    } catch (InterruptedException ignored) {}
                    continue;
                }

                // Catch-up limitado (evita espiral de muerte si hay un spike)
                int steps = 0;
                while (now >= nextStep && steps < 5) {
                    aplicarInputServidor(MOVE_SPEED);
                    world.step(DT, 6, 2);
                    nextStep += stepNs;
                    steps++;
                }

                // Snapshot de red a tasa fija
                now = System.nanoTime();
                if (now >= nextNetSendNs) {
                    enviarPosiciones(false);
                    // avanza el siguiente tick sin drift
                    do {
                        nextNetSendNs += netNs;
                    } while (now >= nextNetSendNs);
                }
            }
        }, "ServerPhysicsLoop").start();
    }

    private void aplicarInputServidor(float speed) {
        aplicarVelocidad(b1, dx[1], dy[1], speed);
        aplicarVelocidad(b2, dx[2], dy[2], speed);
    }

    private void aplicarVelocidad(Body b, int dx, int dy, float speed) {
        if (b == null) return;

        tmpVel.set(dx, dy);
        if (tmpVel.len2() > 1f) tmpVel.nor();
        tmpVel.scl(speed);

        b.setLinearVelocity(tmpVel);
    }

    private void enviarPosiciones(boolean force) {
        if (server == null) return;

        // Si forzamos (spawn/door/start), adelantamos el siguiente tick de red para evitar doble envío inmediato.
        if (force) {
            long now = System.nanoTime();
            nextNetSendNs = now + (1_000_000_000L / NET_HZ);
        }

        if (b1 != null) {
            Vector2 p1 = b1.getPosition();
            server.sendMessageToAll("UpdatePosition:1:" + p1.x + ":" + p1.y);
        }
        if (b2 != null) {
            Vector2 p2 = b2.getPosition();
            server.sendMessageToAll("UpdatePosition:2:" + p2.x + ":" + p2.y);
        }
    }

    public void stop() {
        running = false;
        if (world != null) world.dispose();
        world = null;
        b1 = null;
        b2 = null;
        map = null;
    }
}
