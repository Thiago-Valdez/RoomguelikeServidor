package server;

import com.badlogic.gdx.Gdx;
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
import mapa.trampilla.DatosTrampilla;
import entidades.GestorDeEntidades;
import entidades.enemigos.Enemigo;
import entidades.enemigos.EnemigosDesdeTiled;
import entidades.items.Item;
import entidades.datos.Estilo;
import entidades.datos.Genero;
import entidades.personajes.Jugador;

public class GameControllerImpl implements GameController {

    private static final float DT = 1f / 60f;
    private static final float MOVE_SPEED = 160f;
    private static final int NET_HZ = 20; // ✅ 20 updates/s (liviano y suficiente)
    private static final int HUD_HZ = 2;  // ✅ re-sync HUD cada 0.5s (UDP puede perder)
    private ServerThread server;

    // ✅ fin de partida (GameOver) - evita disparar múltiples veces
    private volatile boolean gameOverDisparado = false;

    private World world;
    private Body b1, b2;

    // ✅ Jugadores autoritativos en server (stats/inventario)
    private Jugador j1;
    private Jugador j2;

    // índices 1..2 usados
    private final int[] dx = new int[3];
    private final int[] dy = new int[3];

    private volatile boolean running = false;

    // ✅ Física corre en hilo dedicado
    private volatile Thread physicsThread = null;

    // ==================================================
    // ✅ Stop modes
    // - fullReset=true  : fin de partida / reinicio total (borra jugadores)
    // - fullReset=false : cambio de nivel (conserva inventario/vida/stats)
    // ==================================================
    private volatile boolean stopFullReset = true;

    // ✅ Red: snapshots a tasa fija
    private volatile long nextNetSendNs = 0L;
    private volatile long nextHudSendNs = 0L;

    // ✅ map pre-cargado en hilo GL
    private TiledMap map;

    // =====================
    // Config de partida (para puertas autoritativas)
    // =====================
    private long seedPartida = 0L;
    private int nivelPartida = 1;

    private DisposicionMapa disposicion;
    private Habitacion salaActual = Habitacion.INICIO_1;

    // =====================
    // Sala despejada (server-driven)
    // =====================
    private final HashSet<Habitacion> salasDespejadas = new HashSet<>();


    // =====================
    // Items (server-driven)
    // =====================
    private GestorDeEntidades gestorEntidades;
    private final IdentityHashMap<Item, Integer> idPorItem = new IdentityHashMap<>();
    private final HashMap<Integer, Item> itemPorId = new HashMap<>();
    private int nextItemId = 1;

    // =====================
    // Enemigos (server-driven)
    // =====================
    private final IdentityHashMap<Enemigo, Integer> idPorEnemigo = new IdentityHashMap<>();
    private final HashMap<Integer, Enemigo> enemigoPorId = new HashMap<>();
    private int nextEnemyId = 1;
    private final HashSet<Habitacion> salasConEnemigos = new HashSet<>();

    // =====================
    // Daño (server-driven)
    // =====================
    private static final class PendingDamage {
        final int playerNum;
        PendingDamage(int playerNum) { this.playerNum = playerNum; }
    }

    private final ArrayDeque<PendingDamage> pendingDamages = new ArrayDeque<>();
    private final HashSet<Integer> damagesEncolados = new HashSet<>();
    private static final float HIT_COOLDOWN_S = 0.60f;

    private static final class PendingPickup {
        final int playerNum;
        final int itemId;
        PendingPickup(int playerNum, int itemId) {
            this.playerNum = playerNum;
            this.itemId = itemId;
        }
    }

    private final ArrayDeque<PendingPickup> pendingPickups = new ArrayDeque<>();
    private final HashSet<Integer> pickupsEncolados = new HashSet<>();

    // Anti-retrigger de puertas (el contacto puede disparar varias veces)
    private long lastDoorNs = 0L;
    private static final long DOOR_COOLDOWN_NS = 400_000_000L; // 400ms

    // ✅ Nunca teletransportar/modificar cuerpos dentro de callbacks de Box2D.
    // Encolamos la transición y la procesamos fuera del step para evitar crashes nativos.
    private static final class PendingDoor {
        final int playerNum;
        final Habitacion origen;
        final Habitacion destino;
        final Direccion direccion;

        PendingDoor(int playerNum, Habitacion origen, Habitacion destino, Direccion direccion) {
            this.playerNum = playerNum;
            this.origen = origen;
            this.destino = destino;
            this.direccion = direccion;
        }
    }

private static class PendingRoomClear {
    final int playerNum;
    final Habitacion sala;
    PendingRoomClear(int playerNum, Habitacion sala) {
        this.playerNum = playerNum;
        this.sala = sala;
    }
}



    private volatile PendingDoor pendingDoor = null;
    private volatile PendingRoomClear pendingRoomClear = null;

    // =====================
    // Fin de nivel (server autoritativo)
    // =====================
    private FisicaMundo fisicaMundo;
    private Body trampillaBody;
    private Habitacion salaTrampilla;
    private volatile boolean advanceLevelRequested = false;
    private volatile boolean advancingLevelNow = false;
    private long lastAdvanceNs = 0L;
    private static final long ADVANCE_COOLDOWN_NS = 1_000_000_000L; // 1s
    private static final int NIVEL_MAX = 3;


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

        gameOverDisparado = false;

        initFisicaServidor();
        salasDespejadas.add(salaActual);


    // ✅ estado inicial para HUD (vida + inventario)
        enviarHudAll();

        enviarPosiciones(true);
        startLoop();
    }

    private void initFisicaServidor() {
        if (map == null) {
            throw new IllegalStateException("TiledMap no seteado. Llamá setTiledMap(map) antes de startGame().");
        }

        // ✅ limpiar estado por nivel
        salasDespejadas.clear();
        salasConEnemigos.clear();

        idPorItem.clear();
        itemPorId.clear();
        nextItemId = 1;

        idPorEnemigo.clear();
        enemigoPorId.clear();
        nextEnemyId = 1;

        pendingDamages.clear();
        damagesEncolados.clear();
        pendingPickups.clear();
        pickupsEncolados.clear();

        pendingDoor = null;
        pendingRoomClear = null;

        limpiarTrampilla();

        if (world != null) {
            world.dispose();
            world = null;
        }

        world = new World(new Vector2(0f, 0f), true);

        // ✅ colisiones desde Tiled
        ColisionesDesdeTiled.crearColisiones(map, world);

        // ✅ Generar disposición (server autoritativo)
        GeneradorMapa.Configuracion cfg = new GeneradorMapa.Configuracion();
        cfg.nivel = Math.max(1, nivelPartida);
        cfg.semilla = seedPartida;

        List<Habitacion> todasLasHabitaciones = Arrays.asList(Habitacion.values());
        GrafoPuertas grafo = new GrafoPuertas(todasLasHabitaciones, new Random(cfg.semilla));

        GeneradorMapa generador = new GeneradorMapa(cfg, grafo);
        disposicion = generador.generar();
        salaActual = disposicion.salaInicio();

        // ✅ Spawn basado en sala INICIO (evita hardcodes que rompen entre niveles)
        Vector2 sp1 = calcularSpawnJugador(salaActual, 1);
        Vector2 sp2 = calcularSpawnJugador(salaActual, 2);

        b1 = crearJugadorBody(sp1.x, sp1.y);
        b2 = crearJugadorBody(sp2.x, sp2.y);

        b1.setBullet(true);
        b2.setBullet(true);

        b1.setLinearDamping(6f);
        b2.setLinearDamping(6f);

        // ✅ Jugadores autoritativos: stats/inventario viven en server.
        // Al pasar de nivel NO recreamos jugadores (si no se pierde inventario/vida).
        if (j1 == null) j1 = new Jugador(1, "J1", Genero.MASCULINO, Estilo.CLASICO);
        if (j2 == null) j2 = new Jugador(2, "J2", Genero.FEMENINO, Estilo.CLASICO);

        // Importante: Jugador.setCuerpoFisico setea body.userData = Jugador
        j1.setCuerpoFisico(b1);
        j2.setCuerpoFisico(b2);

        // ✅ Asegura que stats dependientes de items (velocidad/vidaMax) estén aplicados al iniciar el nivel.
        // (Idempotente: no debe curar ni resetear vida actual.)
        try { j1.reaplicarEfectosDeItems(); } catch (Exception ignored) {}
        try { j2.reaplicarEfectosDeItems(); } catch (Exception ignored) {}

        // ✅ Sensores de puertas en el SERVER (autoritativo)
        fisicaMundo = new FisicaMundo(world);
        InicializadorSensoresPuertas.generarSensoresPuertas(fisicaMundo, disposicion, reg -> { /* no visuales */ });

        // ✅ Gestor de entidades en server (items + enemigos)
        gestorEntidades = new GestorDeEntidades(world);
        // ✅ CRÍTICO: si el gestor es nuevo, hay que volver a registrar jugadores,
        // si no, el HUD/estado del cliente puede "volver a defaults".
        try { gestorEntidades.registrarJugador(j1); } catch (Exception ignored) {}
        try { gestorEntidades.registrarJugador(j2); } catch (Exception ignored) {}

        // ✅ estado inicial: sala inicio siempre despejada
        salasDespejadas.add(salaActual);

        // ✅ Enemigos autoritativos: spawnea los enemigos definidos en Tiled para la sala inicial
        spawnearEnemigosDeSalaSiHaceFalta(salaActual);

        // ✅ Puertas/Items/Daño/Trampilla: el SERVER detecta contacto y emite eventos
        world.setContactListener(new ContactListener() {
            @Override
            public void beginContact(Contact contact) {
                Fixture a = contact.getFixtureA();
                Fixture b = contact.getFixtureB();
                tryDoor(a, b);
                tryDoor(b, a);

                // 📦 Pickups autoritativos
                tryPickup(a, b);
                tryPickup(b, a);

                // ⚔️ Daño autoritativo (jugador <-> enemigo)
                tryDamage(a, b);
                tryDamage(b, a);

                // 🕳️ Fin de nivel autoritativo (trampilla)
                tryFinNivel(a, b);
                tryFinNivel(b, a);
            }
            @Override public void endContact(Contact contact) {}
            @Override public void preSolve(Contact contact, Manifold oldManifold) {}
            @Override public void postSolve(Contact contact, ContactImpulse impulse) {}
        });
    }

    private Vector2 calcularSpawnJugador(Habitacion sala, int playerNum) {
        if (sala == null) return new Vector2(0f, 0f);
        float baseX = sala.gridX * sala.ancho;
        float baseY = sala.gridY * sala.alto;

        float cx = baseX + sala.ancho / 2f;
        float cy = baseY + sala.alto / 2f;

        float off = 32f;
        float x = (playerNum == 2) ? (cx + off) : (cx - off);
        float y = cy;

        return new Vector2(x, y);
    }

    private void limpiarTrampilla() {
        if (fisicaMundo != null && trampillaBody != null) {
            try { fisicaMundo.destruirBody(trampillaBody); } catch (Exception ignored) {}
        }
        trampillaBody = null;
        salaTrampilla = null;
    }

    private void tryPickup(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;
        if (!"jugador".equals(jugadorFx.getUserData())) return;

        Object ud = otroFx.getUserData();
        if (!(ud instanceof Item)) return;

        Item item = (Item) ud;
        int itemId = idPorItem.getOrDefault(item, -1);
        if (itemId <= 0) {
            // si todavía no le asignamos id (spawn reciente), se lo asignamos acá
            itemId = asignarIdItem(item);
        }

        // evita encolar muchas veces el mismo item
        if (pickupsEncolados.contains(itemId)) return;

        Object bUd = jugadorFx.getBody() != null ? jugadorFx.getBody().getUserData() : null;
        int playerNum = 1;
        if (bUd instanceof Jugador) playerNum = ((Jugador) bUd).getId();
        else if (bUd instanceof Integer) playerNum = (Integer) bUd;

        pickupsEncolados.add(itemId);
        pendingPickups.addLast(new PendingPickup(playerNum, itemId));
    }

    private void tryDamage(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;

        Object jUd = jugadorFx.getBody() != null ? jugadorFx.getBody().getUserData() : null;
        if (!(jUd instanceof Jugador j)) return;

        Object oUd = otroFx.getBody() != null ? otroFx.getBody().getUserData() : null;
        if (!(oUd instanceof Enemigo)) return;

        int playerNum = j.getId();
        if (playerNum < 1 || playerNum > 2) return;

        // Evita encolar múltiples veces por frame
        if (damagesEncolados.contains(playerNum)) return;
        damagesEncolados.add(playerNum);
        pendingDamages.addLast(new PendingDamage(playerNum));
    }

    private int asignarIdItem(Item item) {
        Integer existing = idPorItem.get(item);
        if (existing != null) return existing;
        int id = nextItemId++;
        idPorItem.put(item, id);
        itemPorId.put(id, item);
        return id;
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

@Override
public void roomClearRequest(int playerNum, String sala) {
    // ⚠️ viene desde el hilo de red => NO tocar Box2D acá. Solo encolamos.
    if (sala == null || sala.isBlank()) return;
    try {
        Habitacion h = Habitacion.valueOf(sala.trim());
        pendingRoomClear = new PendingRoomClear(playerNum, h);
    } catch (IllegalArgumentException ignored) {}
}

@Override
public void nextLevelRequest(int playerNum) {
    // Fallback: sólo aceptamos si estamos en JEFE y está despejada.
    if (salaActual == null) return;
    if (!salaActual.name().startsWith("JEFE")) return;
    if (!salaEstaDespejada(salaActual)) return;

    long now = System.nanoTime();
    if (now - lastAdvanceNs < ADVANCE_COOLDOWN_NS) return;
    lastAdvanceNs = now;
    advanceLevelRequested = true;
}



    private void tryDoor(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;

        // jugador fixture identificada por userData = "jugador"
        if (!"jugador".equals(jugadorFx.getUserData())) return;

        Object ud = otroFx.getUserData();
        if (!(ud instanceof DatosPuerta)) return;
        DatosPuerta puerta = (DatosPuerta) ud;

        // Solo vale si estoy en la sala ORIGEN de esa puerta
        if (puerta.origen() != salaActual) return;

        // cooldown (evita disparos múltiples por el mismo contacto)
        long now = System.nanoTime();
        if (now - lastDoorNs < DOOR_COOLDOWN_NS) return;
        lastDoorNs = now;

        Object bUd = jugadorFx.getBody() != null ? jugadorFx.getBody().getUserData() : null;
        int playerNum = 1;
        if (bUd instanceof Jugador) {
            playerNum = ((Jugador) bUd).getId();
        } else if (bUd instanceof Integer) {
            playerNum = (Integer) bUd;
        }

        // ✅ IMPORTANTE: NO llamamos a door() acá.
        // Estamos dentro de un callback de colisión de Box2D. Modificar cuerpos acá puede
        // crashear nativamente. Encolamos y lo procesamos fuera del step.
        if (pendingDoor == null) {
            pendingDoor = new PendingDoor(playerNum, puerta.origen(), puerta.destino(), puerta.direccion());
        }
    }

    // 🕳️ Fin de nivel autoritativo (trampilla)
    // Se llama desde beginContact. IMPORTANTE: NO avanzar el nivel directamente dentro del callback de Box2D.
    // Solo encolamos la solicitud y el loop la procesa fuera del step.
    private void tryFinNivel(Fixture jugadorFx, Fixture otroFx) {
        if (jugadorFx == null || otroFx == null) return;

        // El fixture del jugador se marca con userData = "jugador"
        if (!"jugador".equals(jugadorFx.getUserData())) return;

        // El sensor de trampilla usa DatosTrampilla como userData
        Object ud = otroFx.getUserData();
        if (!(ud instanceof DatosTrampilla)) return;

        // Anti-retrigger (múltiples beginContact seguidos)
        long now = System.nanoTime();
        if (now - lastAdvanceNs < ADVANCE_COOLDOWN_NS) return;
        lastAdvanceNs = now;

        // Seguridad extra: solo permitir avanzar desde sala JEFE despejada
        if (salaActual == null) return;
        if (!salaActual.name().startsWith("JEFE")) return;
        if (!salaEstaDespejada(salaActual)) return;

        advanceLevelRequested = true;
    }


    private void startLoop() {
        if (running) return;
        running = true;

        // ✅ Física a 60Hz en tiempo real + snapshots de red a tasa fija (NET_HZ).
        Thread physicsThreadLocal = new Thread(() -> {
            final long stepNs = (long) (DT * 1_000_000_000L);
            final long netNs = 1_000_000_000L / NET_HZ;
            final long hudNs = 1_000_000_000L / HUD_HZ;

            long nextStep = System.nanoTime();
            nextNetSendNs = nextStep; // primer snapshot inmediato
            nextHudSendNs = nextStep; // primer HUD inmediato

            try {

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
                    aplicarInputServidor();
                    world.step(DT, 6, 2);

                    // tick de estados/cooldowns autoritativos de jugador (iframes, cooldownDanio)
                    if (j1 != null) j1.tick(DT);
                    if (j2 != null) j2.tick(DT);

                    nextStep += stepNs;
                    steps++;
                }

                // ✅ Spawns de items (BOTIN) desde server (no cliente)
                if (gestorEntidades != null && salaActual != null) {
                    gestorEntidades.actualizar(DT, salaActual);
                    enviarSpawnsItemsNuevos();
                }

                // ✅ Update de enemigos solo en la sala actual (AI simple)
                if (gestorEntidades != null && salaActual != null) {
                    actualizarEnemigosDeSalaActual(DT);
                }

                // ✅ Auto-clear dinámico para COMBATE/JEFE cuando ya no quedan enemigos.
                checkAutoClearSalaActual();

                // ✅ En JEFE: si la sala está despejada, spawneamos trampilla autoritativa.
                actualizarTrampilla();

                // ✅ Fin de nivel autoritativo
                if (advanceLevelRequested) {
                    advanceLevelRequested = false;
                    solicitarAvanceNivel();
                }

                // ✅ Procesar transición de puerta fuera de callbacks de colisión.
                PendingDoor pd = pendingDoor;
                if (pd != null) {
                    pendingDoor = null;

                    // ✅ Si la sala actual está bloqueada (puzzle no resuelto), ignoramos la puerta.
                    if (requiereSalaDespejada(salaActual) && !salaEstaDespejada(salaActual)) {
                        // opcional: podríamos avisar a los clientes, pero con MVP alcanza con ignorar.
                        continue;
                    }

                    // Ejecuta transición autoritativa (teleporta + UpdateRoom + snapshot)
                    door(pd.playerNum, pd.origen.name(), pd.destino.name(), pd.direccion.name());

                    // En tu juego ambos jugadores viajan juntos.
                    salaActual = pd.destino;

                    // ✅ Al entrar a una nueva sala, spawnea enemigos (si existen en Tiled)
                    spawnearEnemigosDeSalaSiHaceFalta(salaActual);
                }

                // ✅ Procesar pickups fuera de callbacks
                procesarPickupsPendientes();

                // ✅ Procesar daño fuera de callbacks
                procesarDaniosPendientes();

                // ✅ Procesar sala despejada (puzzle resuelto) fuera de callbacks
                procesarRoomClearPendiente();

                // ✅ HUD re-sync (vida/inventario) a tasa fija
                now = System.nanoTime();
                if (now >= nextHudSendNs) {
                    enviarHudAll();
                    do {
                        nextHudSendNs += hudNs;
                    } while (now >= nextHudSendNs);
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

            } finally {
                // ✅ Cleanup seguro: no dejamos un World vivo mientras otro hilo podría recrearlo.
                try {
                    if (world != null) world.dispose();
                } catch (Exception ignored) {}
                world = null;
                b1 = null;
                b2 = null;

                // ✅ Importante: al pasar de nivel NO borramos jugadores.
                // Solo soltamos sus bodies (ya no válidos porque el World se destruyó).
                if (stopFullReset) {
                    j1 = null;
                    j2 = null;
                } else {
                    try { if (j1 != null) j1.setCuerpoFisico(null); } catch (Exception ignored) {}
                    try { if (j2 != null) j2.setCuerpoFisico(null); } catch (Exception ignored) {}
                }

                trampillaBody = null;
                salaTrampilla = null;
                fisicaMundo = null;
                physicsThread = null;
            }
        }, "ServerPhysicsLoop");
            physicsThread = physicsThreadLocal;
            physicsThreadLocal.start();
}


    private void checkAutoClearSalaActual() {
        if (server == null || gestorEntidades == null || salaActual == null) return;
        if (!requiereSalaDespejada(salaActual)) return;
        if (!autoClearSiNoHayEnemigos(salaActual)) return;
        if (salasDespejadas.contains(salaActual)) return;

        int count = gestorEntidades.getEnemigosDeSala(salaActual).size();
        if (count == 0) {
            salasDespejadas.add(salaActual);
            server.sendMessageToAll("RoomClear:" + salaActual.name());
        }
    }

    private void actualizarTrampilla() {
        if (fisicaMundo == null || salaActual == null) return;

        // Si cambiamos de sala, destruimos la trampilla previa.
        if (salaTrampilla != null && salaActual != salaTrampilla) {
            limpiarTrampilla();
        }

        // Solo en JEFE, y solo cuando la sala esté resuelta (server autoritativo)
        if (!salaActual.name().startsWith("JEFE")) return;
        if (!salaEstaDespejada(salaActual)) return;

        if (trampillaBody != null) return;

        float size = 16f;
        float baseX = salaActual.gridX * salaActual.ancho;
        float baseY = salaActual.gridY * salaActual.alto;
        float x = baseX + salaActual.ancho / 2f - size / 2f;
        float y = baseY + salaActual.alto / 2f - size / 2f;

        DatosTrampilla dt = new DatosTrampilla(salaActual);
        trampillaBody = fisicaMundo.crearSensorCaja(x, y, size, size, dt);
        salaTrampilla = salaActual;
    }

    private void solicitarAvanceNivel() {
        if (advancingLevelNow) return;
        if (server == null) return;

        advancingLevelNow = true;

        // Ejecutamos el reinicio del nivel en el hilo principal de LibGDX.
        Gdx.app.postRunnable(() -> {
            try {
                avanzarNivelAutoritativo();
            } catch (Throwable t) {
                System.out.println("[SERVER] Error avanzando nivel: " + t.getMessage());
                t.printStackTrace();
            } finally {
                advancingLevelNow = false;
            }
        });
    }

    private void avanzarNivelAutoritativo() {

        // ✅ Si se completó el nivel 3 -> fin del juego
        if (nivelPartida >= NIVEL_MAX) {
            if (server != null) {
                server.sendMessageToAll("Win");
                System.out.println("[SERVER] WIN enviado (se completaron " + NIVEL_MAX + " niveles)");
            }

            // detener simulación
            try { stop(); } catch (Throwable ignored) {}

            // liberar lobby para volver a jugar sin reiniciar server
            if (server != null) server.resetLobby();
            return;
        }

        // ✅ Caso normal: pasar al siguiente nivel
        nivelPartida += 1;
        seedPartida = System.currentTimeMillis();

        if (server != null) {
            server.sendMessageToAll("Start:" + seedPartida + ":" + nivelPartida);
            System.out.println("[SERVER] Start enviado seed=" + seedPartida + " nivel=" + nivelPartida);
        }

        // ✅ Cambio de nivel: detenemos simulación sin borrar jugadores.
        stop(false);
        startGame();
    }


    private void enviarSpawnsItemsNuevos() {
        if (server == null || gestorEntidades == null) return;

        for (Item item : gestorEntidades.getItemsMundo()) {
            if (item == null) continue;
            Integer id = idPorItem.get(item);
            if (id == null) {
                id = asignarIdItem(item);

                Body body = gestorEntidades.getCuerpoItem(item);
                if (body != null) {
                    Vector2 p = body.getPosition();
                    // SpawnItem:id:tipo:x:y
                    server.sendMessageToAll("SpawnItem:" + id + ":" + item.getTipo().name() + ":" + p.x + ":" + p.y);
                }
            }
        }
    }

    // =====================
    // Enemigos (server-driven)
    // =====================

    private int asignarIdEnemigo(Enemigo e) {
        Integer existing = idPorEnemigo.get(e);
        if (existing != null) return existing;
        int id = nextEnemyId++;
        idPorEnemigo.put(e, id);
        enemigoPorId.put(id, e);
        return id;
    }

    private void spawnearEnemigosDeSalaSiHaceFalta(Habitacion sala) {
        if (server == null || gestorEntidades == null || map == null) return;
        if (sala == null) return;
        if (salasConEnemigos.contains(sala)) {
            // Ya spawneamos esta sala: igual re-emitimos spawns por si un cliente se desincronizo.
            enviarSpawnsEnemigosDeSala(sala);
            return;
        }

        // Crea enemigos definidos en Tiled (layer "enemigos") filtrando por propiedad "sala".
        EnemigosDesdeTiled.crearEnemigosDesdeMapa(map, sala, world, gestorEntidades);
        salasConEnemigos.add(sala);

        enviarSpawnsEnemigosDeSala(sala);

        // ✅ Si la sala requiere despejarse pero no tiene enemigos,
        // solo la marcamos despejada automáticamente si es COMBATE/JEFE.
        // En ACERTIJO NO: se despeja solo cuando llega RoomClearReq (puzzle resuelto).
        if (requiereSalaDespejada(sala)) {
            if (autoClearSiNoHayEnemigos(sala)) {
                int count = gestorEntidades.getEnemigosDeSala(sala).size();
                if (count == 0 && !salasDespejadas.contains(sala)) {
                    salasDespejadas.add(sala);
                    if (server != null) server.sendMessageToAll("RoomClear:" + sala.name());
                }
            }
        } else {
            salasDespejadas.add(sala);
        }

    }

    private void enviarSpawnsEnemigosDeSala(Habitacion sala) {
        if (server == null || gestorEntidades == null || sala == null) return;

        for (Enemigo e : gestorEntidades.getEnemigosDeSala(sala)) {
            if (e == null) continue;
            Integer id = idPorEnemigo.get(e);
            if (id == null) id = asignarIdEnemigo(e);

            Body b = e.getCuerpoFisico();
            if (b == null) continue;
            Vector2 p = b.getPosition();

            // SpawnEnemy:id:nombre:x:y:sala
            server.sendMessageToAll("SpawnEnemy:" + id + ":" + e.getNombre() + ":" + p.x + ":" + p.y + ":" + sala.name());
        }
    }

    private void actualizarEnemigosDeSalaActual(float delta) {
        if (gestorEntidades == null || salaActual == null) return;
        for (Enemigo e : gestorEntidades.getEnemigosDeSala(salaActual)) {
            if (e == null) continue;
            e.actualizar(delta, j1, j2);
        }
    }

    private void procesarPickupsPendientes() {
        if (server == null || gestorEntidades == null) {
            pendingPickups.clear();
            pickupsEncolados.clear();
            return;
        }

        while (!pendingPickups.isEmpty()) {
            PendingPickup pp = pendingPickups.pollFirst();
            if (pp == null) continue;

            Item item = itemPorId.get(pp.itemId);
            if (item == null) {
                pickupsEncolados.remove(pp.itemId);
                continue;
            }

            // ✅ aplica pickup en server (autoritativo): remover del mundo + agregar a inventario del jugador
            gestorEntidades.removerItemDelMundo(item);

            Jugador j = (pp.playerNum == 1) ? j1 : (pp.playerNum == 2 ? j2 : null);
            if (j != null) {
                j.agregarObjeto(item); // aplica efectos (vida/velocidad/...) en server
            }

            // evento al cliente
            server.sendMessageToAll("PickupItem:" + pp.playerNum + ":" + pp.itemId + ":" + item.getTipo().name());
            server.sendMessageToAll("DespawnItem:" + pp.itemId);

            // ✅ HUD actualizado (vida/inventario)
            enviarHud(pp.playerNum);

            // limpiar tracking
            idPorItem.remove(item);
            itemPorId.remove(pp.itemId);
            pickupsEncolados.remove(pp.itemId);

            // manda snapshot para que no quede 1 frame raro
            enviarPosiciones(true);
        }
    }


private void procesarRoomClearPendiente() {
    PendingRoomClear pr = pendingRoomClear;
    if (pr == null) return;
    pendingRoomClear = null;

    Habitacion sala = pr.sala;
    if (sala == null) return;

    // Solo aceptamos si coincide con la sala actual (evita requests viejos)
    if (sala != salaActual) return;

    if (!requiereSalaDespejada(sala)) {
        salasDespejadas.add(sala);
        return;
    }

    if (salasDespejadas.contains(sala)) return;

    // ✅ Marcar sala resuelta + eliminar enemigos de esa sala (server autoritativo)
    salasDespejadas.add(sala);

    // Despawnear enemigos (si existieran)
    if (gestorEntidades != null) {
        List<Enemigo> enemigos = new ArrayList<>(gestorEntidades.getEnemigosDeSala(sala));
        for (Enemigo e : enemigos) {
            if (e == null) continue;
            Integer id = idPorEnemigo.get(e);
            if (id != null && server != null) {
                server.sendMessageToAll("DespawnEnemy:" + id);
            }
            if (id != null) enemigoPorId.remove(id);
            if (e != null) idPorEnemigo.remove(e);
        }
        gestorEntidades.eliminarEnemigosDeSala(sala);
    }

    // Notificar a clientes (para UI/puertas visuales)
    if (server != null) {
        server.sendMessageToAll("RoomClear:" + sala.name());
    }
}


private void procesarDaniosPendientes() {
        if (server == null) {
            pendingDamages.clear();
            damagesEncolados.clear();
            return;
        }

        while (!pendingDamages.isEmpty()) {
            PendingDamage pd = pendingDamages.pollFirst();
            if (pd == null) continue;

            Jugador j = (pd.playerNum == 1) ? j1 : (pd.playerNum == 2 ? j2 : null);
            if (j == null) {
                damagesEncolados.remove(pd.playerNum);
                continue;
            }

            if (j.puedeRecibirDanio()) {
                j.recibirDanio();
                j.marcarHitCooldown(HIT_COOLDOWN_S);

                // Damage:playerId:vida:vidaMax
                server.sendMessageToAll("Damage:" + pd.playerNum + ":" + j.getVida() + ":" + j.getVidaMaxima());
                if (!j.estaViva()) {
                    server.sendMessageToAll("Dead:" + pd.playerNum);

                    // ✅ FIN DE PARTIDA: si muere cualquiera, termina para ambos
                    if (!gameOverDisparado) {
                        gameOverDisparado = true;

                        // avisamos a ambos clientes
                        server.sendMessageToAll("GameOver:" + pd.playerNum);

                        // frenamos la simulación + liberamos mundo (SERVER: no hay hilo GL)
                        try {
                            stop();
                        } catch (Throwable t) {
                            System.out.println("[SERVER] stop() falló: " + t.getMessage());
                        }


                        // liberamos lobby para permitir nueva partida sin reiniciar server
                        server.resetLobby();
                    }
                }

                // HUD dirigido: actualiza al jugador golpeado y el estado público del otro
                enviarHud(pd.playerNum);
                enviarOtherStateAll();
            }

            damagesEncolados.remove(pd.playerNum);
        }
    }

    private void aplicarInputServidor() {
        float s1 = (j1 != null) ? j1.getVelocidad() : MOVE_SPEED;
        float s2 = (j2 != null) ? j2.getVelocidad() : MOVE_SPEED;
        aplicarVelocidad(b1, dx[1], dy[1], s1);
        aplicarVelocidad(b2, dx[2], dy[2], s2);
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

        // Enemigos: solo sincronizamos los de la sala actual (lo demás no se renderiza)
        if (gestorEntidades != null && salaActual != null) {
            for (Enemigo e : gestorEntidades.getEnemigosDeSala(salaActual)) {
                if (e == null || e.getCuerpoFisico() == null) continue;
                Integer id = idPorEnemigo.get(e);
                if (id == null) id = asignarIdEnemigo(e);
                Vector2 pe = e.getCuerpoFisico().getPosition();
                server.sendMessageToAll("UpdateEnemy:" + id + ":" + pe.x + ":" + pe.y);
            }
        }
    }

    private boolean salaEstaDespejada(Habitacion sala) {
        return sala != null && salasDespejadas.contains(sala);
    }

    /**
     * MVP robusto: decidimos por el nombre del enum para no depender de APIs
     * (por si Habitacion no expone getTipo()).
     *
     * Con tus enums típicos: INICIO_1, BOTIN_1, ACERTIJO_1, COMBATE_1, JEFE_1, etc.
     */
    private boolean requiereSalaDespejada(Habitacion sala) {
        if (sala == null) return false;
        String n = sala.name();
        return n.startsWith("COMBATE") || n.startsWith("ACERTIJO") || n.startsWith("JEFE");
    }

    private boolean autoClearSiNoHayEnemigos(Habitacion sala) {
        if (sala == null) return false;
        String n = sala.name();
        return n.startsWith("COMBATE") || n.startsWith("JEFE");
    }



    // =====================
    // HUD / Inventario (server-driven)
    // =====================

    private void enviarHudAll() {
        enviarHud(1);
        enviarHud(2);

        // ✅ MVP UI del otro jugador: cada cliente recibe vida/vidaMax del otro.
        enviarOtherStateAll();
    }

    /**
     * MVP: estado público mínimo del otro jugador (solo vida/vidaMax).
     * Se envía de forma dirigida:
     * - al cliente de P1 le llega estado de P2
     * - al cliente de P2 le llega estado de P1
     */
    private void enviarOtherStateAll() {
        enviarOtherStateTo(1);
        enviarOtherStateTo(2);
    }

    private void enviarOtherStateTo(int receiverPlayerNum) {
        if (server == null) return;
        if (receiverPlayerNum != 1 && receiverPlayerNum != 2) return;

        int otherId = (receiverPlayerNum == 1) ? 2 : 1;
        Jugador other = (otherId == 1) ? j1 : j2;
        if (other == null) {
            server.sendMessageToPlayer(receiverPlayerNum, "Other:" + otherId + ":0:0");
            return;
        }

        server.sendMessageToPlayer(receiverPlayerNum,
                "Other:" + otherId + ":" + other.getVida() + ":" + other.getVidaMaxima());
    }

    private void enviarHud(int playerNum) {
        if (server == null) return;
        Jugador j = (playerNum == 1) ? j1 : (playerNum == 2 ? j2 : null);
        if (j == null) return;

        StringBuilder sb = new StringBuilder();
        for (entidades.items.Item it : j.getObjetos()) {
            if (it == null || it.getTipo() == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(it.getTipo().name());
        }

        // ✅ HUD es por-jugador: lo enviamos SOLO al cliente dueño de ese playerNum.
        // (Los eventos de mundo como Spawn/Despawn siguen broadcast.)
        if (sb.length() == 0) {
            server.sendMessageToPlayer(playerNum, "Hud:" + playerNum + ":" + j.getVida() + ":" + j.getVidaMaxima());
        } else {
            server.sendMessageToPlayer(playerNum, "Hud:" + playerNum + ":" + j.getVida() + ":" + j.getVidaMaxima() + ":" + sb);
        }
    }

    /**
     * ✅ ONLINE: snapshot autoritativo de HUD para un cliente.
     * Se usa cuando el cliente avisa "Ready" luego de recrear el World (inicio de partida o cambio de nivel).
     * Envía: Hud (vida/vidaMax/inventario) + Other (vida del otro jugador).
     */
    public void enviarSnapshotHudPara(int playerNum) {
        try { enviarHud(playerNum); } catch (Exception ignored) {}
        try { enviarOtherStateTo(playerNum); } catch (Exception ignored) {}
    }

    /**
     * Detiene el loop de física.
     * @param fullReset si es true, se borran jugadores (fin de partida).
     *                 si es false, se conservan jugadores/inventario/vida (cambio de nivel).
     */
    public void stop(boolean fullReset) {
        // ✅ El finally del hilo decide si nullear jugadores.
        stopFullReset = fullReset;

        // Señalamos al loop de física que termine.
        running = false;

        // Esperamos a que el hilo de física salga antes de tocar Box2D.
        Thread t = physicsThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // Si por algún motivo el hilo ya no existe y el world sigue vivo, liberamos acá.
        if ((t == null || !t.isAlive()) && world != null) {
            try { world.dispose(); } catch (Exception ignored) {}
            world = null;
        }

        b1 = null;
        b2 = null;
        if (fullReset) {
            j1 = null;
            j2 = null;
        } else {
            // bodies ya no sirven: se recrean en initFisicaServidor
            try { if (j1 != null) j1.setCuerpoFisico(null); } catch (Exception ignored) {}
            try { if (j2 != null) j2.setCuerpoFisico(null); } catch (Exception ignored) {}
        }
        trampillaBody = null;
        salaTrampilla = null;
        fisicaMundo = null;
        physicsThread = null;
    }

    public void stop() {
        stop(true);
    }
}
