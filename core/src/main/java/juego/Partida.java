package juego;

import java.util.*;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Fixture;
import com.badlogic.gdx.physics.box2d.World;

import camara.CamaraDeSala;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import config.OpcionesPanel;
import control.input.ControlJugador;
import control.puzzle.ControlPuzzlePorSala;
import control.salas.GestorSalas;
import entidades.Entidad;
import entidades.GestorDeEntidades;
import entidades.datos.*;
import entidades.enemigos.*;
import entidades.items.*;
import entidades.personajes.*;
import entidades.sprites.*;
import fisica.BotonesDesdeTiled;
import fisica.ColisionesDesdeTiled;
import fisica.FisicaMundo;
import fisica.GeneradorSensoresPuertas;
import interfaces.hud.HudJuego;
import interfaces.listeners.ListenerCambioSala;
import io.github.principal.Principal;
import juego.contactos.EnrutadorContactosPartida;
import juego.inicializacion.ContextoPartida;
import juego.inicializacion.InicializadorPartida;
import juego.inicializacion.InicializadorSensoresPuertas;
import juego.inicializacion.InicializadorSpritesPartida;
import juego.sistemas.CanalRenderizadoPartida;
import juego.sistemas.ProcesadorColasEventos;
import juego.sistemas.SistemaActualizacionPartida;
import juego.sistemas.ContextoActualizacionPartida;
import juego.sistemas.SistemaFinNivel;
import juego.sistemas.SistemaSpritesEntidades;
import juego.sistemas.SistemaTransicionSala;
import juego.eventos.ColaEventos;
import juego.eventos.EventoDanio;
import juego.eventos.EventoFinNivel;
import mapa.botones.*;
import mapa.generacion.*;
import mapa.model.*;
import mapa.puertas.*;

/**
 * Contenedor del gameplay.
 *
 * Objetivo: que JuegoPrincipal quede mínimo y que acá esté TODA la lógica del loop.
 */
public class Partida {

    private final Principal game;

    // --- Mundo físico (único) ---
    private World world;
    private FisicaMundo fisica;

    // --- Render ---
    private SpriteBatch batch;
    private ShapeRenderer shapeRendererMundo;
    private ShapeRenderer debugRenderer = new ShapeRenderer();


    // --- Mapa Tiled ---
    private TiledMap mapaTiled;
    private OrthogonalTiledMapRenderer mapaRenderer;

    // --- Cámara ---
    private CamaraDeSala camaraSala;

    // --- Mapa lógico ---
    private DisposicionMapa disposicion;
    private Habitacion salaActual;
    private ControlPuzzlePorSala controlPuzzle;

    // --- Gestión ---
    private GestorSalas gestorSalas;
    private GestorDeEntidades gestorEntidades;

    // --- Jugadores ---
    private Jugador jugador1;
    private Jugador jugador2;
    private ControlJugador controlJugador1;
    private ControlJugador controlJugador2;

    // --- HUD ---
    private HudJuego hud;
    public List<Habitacion> salasDelPiso;

    private Stage pauseStage;
    private Skin skin;
    private boolean opcionesAbiertas = false;
    private com.badlogic.gdx.InputProcessor inputAnterior;



    // --- Listeners de cambio de sala (HUD, logs, etc.) ---
    private final List<ListenerCambioSala> listenersCambioSala = new ArrayList<>();

    // --- Cola unificada de eventos (evita modificar Box2D dentro del callback) ---
    private final ColaEventos eventos = new ColaEventos();
    // Helpers para dedupe por frame (migrables a sistemas luego)
    private final Set<entidades.items.Item> itemsYaProcesados = new HashSet<>();
    private final Set<Integer> jugadoresDanioFrame = new HashSet<>();


    private final List<BotonVisual> botonesVisuales = new ArrayList<>();
    private Texture texBotonRojo;
    private Texture texBotonAzul;
    private TextureRegion[][] framesBotonRojo;
    private TextureRegion[][] framesBotonAzul;

    private final java.util.List<PuertaVisual> puertasVisuales = new java.util.ArrayList<>();
    private Texture texPuertaAbierta;
    private Texture texPuertaCerrada;
    private TextureRegion regPuertaAbierta;
    private TextureRegion regPuertaCerrada;

    // Trampilla (visual)
    private Texture texTrampilla;
    private TextureRegion regTrampilla;


    private Map<ItemTipo, TextureRegion> spritesItems = new HashMap<>();
    private Map<ItemTipo, Texture> texturasItems = new HashMap<>();

    private boolean gameOverSolicitado = false;
    private float timerGameOver = 0f;

    private static final int NIVEL_FINAL = 3;
    private boolean victoriaSolicitada = false;

    // Persisten durante toda la run (NO se reinician entre niveles)
    private Jugador jugador1Persistente;
    private Jugador jugador2Persistente;
    private boolean runInicializada = false;








    // --- Sistemas (extraídos) ---
    private final SistemaTransicionSala sistemaTransicionSala = new SistemaTransicionSala();
    private final ProcesadorColasEventos procesadorColasEventos = new ProcesadorColasEventos();
    private final SistemaFinNivel sistemaFinNivel = new SistemaFinNivel();

    // Sistemas extraídos
    private SistemaSpritesEntidades sistemaSprites;
    private SistemaActualizacionPartida sistemaActualizacion;
    private final ContextoActualizacionPartida ctxUpdate = new ContextoActualizacionPartida();
    private CanalRenderizadoPartida canalRenderizado;

    // --- Flags ---
    private boolean debugFisica = true;

    private int nivelActual = 1;



    public Partida(Principal game) {
        this.game = game;
    }

    public void start() {
        nivelActual = 1;
        initNivel();
    }

    // ==========================
    // EVENTOS: CAMBIO DE SALA
    // ==========================

    public void agregarListenerCambioSala(ListenerCambioSala listener) {
        if (listener != null && !listenersCambioSala.contains(listener)) {
            listenersCambioSala.add(listener);
        }
    }

    private void notificarCambioSala(Habitacion salaAnterior, Habitacion salaNueva) {
        for (ListenerCambioSala listener : listenersCambioSala) {
            listener.salaCambiada(salaAnterior, salaNueva);
        }
    }

    // ==========================
    // INIT
    // ==========================

        private void resetearEstadoNivel() {
        // Colecciones y estado que NO deben sobrevivir entre niveles
        eventos.clear();
        itemsYaProcesados.clear();
        jugadoresDanioFrame.clear();

        botonesVisuales.clear();
        puertasVisuales.clear();

        // referencias a sprites/frames se recalculan por nivel
        framesBotonRojo = null;
        framesBotonAzul = null;
    }

    private void initRunSiHaceFalta() {
        if (runInicializada) return;

        // si hoy jugador1/jugador2 los crea el inicializador, acá NO lo uses.
        // crealos una vez de forma explícita
        jugador1Persistente = new Jugador(1, "J1", null, null);
        jugador2Persistente = new Jugador(2, "J2", null, null);

        runInicializada = true;
    }

    public void initNivel() {
        resetearEstadoNivel();
        initRunSiHaceFalta();

        // 1) Construcción nivel: mundo + mapa + fisica + tiled + etc
        ContextoPartida ctx = InicializadorPartida.crearContextoNivel(nivelActual, jugador1, jugador2);

        aplicarContexto(ctx);  // Aplica el contexto, pero no reemplaza los jugadores persistentes.
        initOverlayOpciones();

        // 1.1) Sprites UI/sensores
        cargarSpritesBotones();
        cargarSpritesPuertas();
        cargarSpriteTrampilla();
        cargarSpritesItems();

        if (texBotonRojo != null) texBotonRojo.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        if (texBotonAzul != null) texBotonAzul.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        // Crear sensores + visuales
        BotonesDesdeTiled.crearBotones(mapaTiled, world, framesBotonRojo, framesBotonAzul, botonesVisuales);

        // 2) Sprites (usa jugadores persistentes)
        sistemaSprites = InicializadorSpritesPartida.crearSistemaSprites(gestorEntidades, jugador1, jugador2);

        // 3) Sensores de puertas
        InicializadorSensoresPuertas.generarSensoresPuertas(fisica, disposicion, reg -> {
            PuertaVisual pv = reg.visual();
            pv.setFrames(regPuertaAbierta, regPuertaCerrada);
            puertasVisuales.add(pv);
            gestorEntidades.registrarPuertaVisual(reg.origen(), reg.visual());
        });

        // 4) Listeners HUD
        agregarListenerCambioSala(hud);

        // 5) Sistemas
        sistemaActualizacion = new SistemaActualizacionPartida(
            gestorEntidades,
            fisica,
            camaraSala,
            sistemaTransicionSala,
            procesadorColasEventos,
            sistemaSprites
        );

        canalRenderizado = new CanalRenderizadoPartida(
            camaraSala,
            mapaRenderer,
            shapeRendererMundo,
            batch,
            fisica,
            hud,
            gestorEntidades,
            sistemaSprites
        );

        // 6) Contactos
        fisica.setContactListener(new EnrutadorContactosPartida(this));

        // ✅ 7) Reaplicar ítems (por si el inicializador tocó stats base)
        jugador1.reaplicarEfectosDeItems();
        jugador2.reaplicarEfectosDeItems();

        // ✅ 8) Asegurar que los bodies de los jugadores correspondan al *world actual*
        // (si tu InicializadorPartida ya crea los bodies, perfecto; si no, forzá respawn acá)
        gestorEntidades.forzarRespawnJugadoresEnWorldActual(jugador1, jugador2, salaActual);
    }




    private void cargarSpriteTrampilla() {
        // Textura 16x16 (o cualquier tamaño, se dibuja escalada a 16x16)
        try {
            texTrampilla = new Texture(Gdx.files.internal("Trampilla/trampilla.png"));
            texTrampilla.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            regTrampilla = new TextureRegion(texTrampilla);
        } catch (Exception ex) {
            // Si no existe el asset aún, la trampilla seguirá funcionando como sensor.
            texTrampilla = null;
            regTrampilla = null;
        }
    }

    private void aplicarContexto(ContextoPartida ctx) {
        this.world = ctx.world;
        this.fisica = ctx.fisica;

        this.batch = ctx.batch;
        this.shapeRendererMundo = ctx.shapeRendererMundo;

        this.mapaTiled = ctx.mapaTiled;
        this.mapaRenderer = ctx.mapaRenderer;

        this.camaraSala = ctx.camaraSala;

        this.disposicion = ctx.disposicion;
        this.salaActual = ctx.salaActual;
        this.controlPuzzle = ctx.controlPuzzle;
        this.salasDelPiso = ctx.salasDelPiso;

        this.gestorEntidades = ctx.gestorEntidades;
        this.gestorSalas = ctx.gestorSalas;

        this.jugador1 = ctx.jugador1;
        this.jugador2 = ctx.jugador2;
        this.controlJugador1 = ctx.controlJugador1;
        this.controlJugador2 = ctx.controlJugador2;

        this.hud = ctx.hud;
    }

    private void cargarSpritesBotones() {
        // Carga texturas
        texBotonRojo = new Texture(Gdx.files.internal("Botones/boton_rojo.png"));
        texBotonAzul = new Texture(Gdx.files.internal("Botones/boton_azul.png"));

        // Split: 1 fila x 2 columnas (UP/DOWN)
        framesBotonRojo = TextureRegion.split(
            texBotonRojo,
            texBotonRojo.getWidth() / 2,
            texBotonRojo.getHeight()
        );

        framesBotonAzul = TextureRegion.split(
            texBotonAzul,
            texBotonAzul.getWidth() / 2,
            texBotonAzul.getHeight()
        );

        // Validación rápida (para detectar spritesheet mal cortado)
        if (framesBotonRojo.length < 1 || framesBotonRojo[0].length < 2) {
            throw new IllegalStateException("Spritesheet rojo inválido. Se esperaba 1x2 (UP/DOWN).");
        }
        if (framesBotonAzul.length < 1 || framesBotonAzul[0].length < 2) {
            throw new IllegalStateException("Spritesheet azul inválido. Se esperaba 1x2 (UP/DOWN).");
        }
    }

    private void cargarSpritesPuertas() {
        texPuertaAbierta = new Texture(Gdx.files.internal("Puertas/puerta_abierta.png"));
        texPuertaCerrada = new Texture(Gdx.files.internal("Puertas/puerta_cerrada.png"));

        texPuertaAbierta.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        texPuertaCerrada.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        regPuertaAbierta = new TextureRegion(texPuertaAbierta);
        regPuertaCerrada = new TextureRegion(texPuertaCerrada);
    }

    private void cargarSpritesItems() {
        for (ItemTipo tipo : ItemTipo.values()) {
            String archivo = "items/" + tipo.name().toLowerCase() + ".png";
            Gdx.app.log("ITEM_SPRITE", "Cargando: " + archivo);

            Texture tex = new Texture(Gdx.files.internal(archivo)); // <- acá revienta
            tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

            texturasItems.put(tipo, tex);
            spritesItems.put(tipo, new TextureRegion(tex));
        }
    }






    // ==========================
    // GETTERS mínimos (para sistemas / enrutador de contactos)
    // ==========================

    public Habitacion getSalaActual() { return salaActual; }
    public SistemaTransicionSala getSistemaTransicionSala() { return sistemaTransicionSala; }
    public ColaEventos getEventos() { return eventos; }

    public void aplicarDanioPorEnemigo(Jugador jugador, Enemigo enemigo) {
        if (jugador == null) return;

        // Evitar repetir daño si ya está en muerte o inmune
        if (jugador.estaEnMuerte() || jugador.esInmune() || !jugador.estaViva()) return;

        jugador.recibirDanio();

        // iniciar animación de muerte del sprite
        if (sistemaSprites != null) sistemaSprites.iniciarMuerte(jugador);

        // opcional: frenar el cuerpo YA
        if (jugador.getCuerpoFisico() != null) {
            jugador.getCuerpoFisico().setLinearVelocity(0f, 0f);
        }
    }

    public void encolarDanioJugador(int jugadorId, float ex, float ey) {
        if (jugadorId <= 0) return;
        eventos.publicar(new EventoDanio(jugadorId, ex, ey));
    }



    // ==========================
    // LOOP
    // ==========================

        private void prepararContextoActualizacion(float delta) {
        if (ctxUpdate == null) return;

        ctxUpdate.delta = delta;
        ctxUpdate.salaActual = salaActual;

        // Jugadores + input
        ctxUpdate.jugador1 = jugador1;
        ctxUpdate.jugador2 = jugador2;
        ctxUpdate.controlJugador1 = controlJugador1;
        ctxUpdate.controlJugador2 = controlJugador2;

        // Eventos (cola unificada) + helpers
        ctxUpdate.eventos = eventos;
        ctxUpdate.itemsYaProcesados = itemsYaProcesados;
        ctxUpdate.jugadoresDanioFrame = jugadoresDanioFrame;

        // Visuales
        ctxUpdate.botonesVisuales = botonesVisuales;

        // Dependencias
        ctxUpdate.controlPuzzle = controlPuzzle;
        ctxUpdate.gestorSalas = gestorSalas;
        ctxUpdate.disposicion = disposicion;
        ctxUpdate.notificarCambioSala = this::notificarCambioSala;
        ctxUpdate.mapaTiled = mapaTiled;
        ctxUpdate.world = world;
    }

public void render(float delta) {
        if (world == null) return;

        // Toggle opciones con ESC
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (opcionesAbiertas) cerrarOpciones();
            else abrirOpciones();
        }




        canalRenderizado.setPuertasVisuales(puertasVisuales);

        sincronizarEstadoPuertasVisuales();


        if (sistemaActualizacion != null) {
            prepararContextoActualizacion(delta);

            salaActual = sistemaActualizacion.actualizar(ctxUpdate);
            ctxUpdate.salaActual = salaActual;
        }
        // ... después de sistemaActualizacion.actualizar(...)

        actualizarGameOver(delta);
        if (gameOverSolicitado) {
            return; // cortamos el frame para que no siga avanzando lógica/render
        }


        // Actualizar condición de combate/jefe (enemigos vivos) y evaluar puzzle.
        if (controlPuzzle != null && gestorEntidades != null && salaActual != null) {
            int vivos = gestorEntidades.getEnemigosDeSala(salaActual).size();
            controlPuzzle.setEnemigosVivos(salaActual, vivos);
        }

        // Spawnea trampilla en JEFE cuando se cumple: enemigos=0 y ambos botones presionados.
        if (sistemaFinNivel != null) {
            sistemaFinNivel.actualizar(salaActual, controlPuzzle, fisica, regTrampilla);
        }

        // Si algún jugador tocó la trampilla, avanzar de nivel.
        final boolean[] avanzar = {false};
        eventos.drenar(EventoFinNivel.class, ev -> {
            if (ev.sala() == salaActual) avanzar[0] = true;
        });
        if (avanzar[0]) {
            avanzarAlSiguienteNivel();
            return;
        }

        if (canalRenderizado != null) {
            canalRenderizado.render(
                    delta,
                    salaActual,
                    debugFisica,
                    jugador1,
                    jugador2,
                    botonesVisuales,
                    (sistemaFinNivel != null) ? sistemaFinNivel.getTrampillaVisual() : null
            );
        }
        registrarSpritesItemsNuevos();

        // Overlay de opciones arriba del juego (sin pausar lógica)
        if (opcionesAbiertas && pauseStage != null) {
            pauseStage.act(delta);
            pauseStage.draw();
        }
    Body b1 = jugador1.getCuerpoFisico();
    Body b2 = jugador2.getCuerpoFisico();

    canalRenderizado.dibujarDebugBodies(camaraSala.getCamara(), debugRenderer, b1, b2);


    }

    private void avanzarAlSiguienteNivel() {
        if (sistemaFinNivel != null && fisica != null) sistemaFinNivel.limpiar(fisica);

        if (nivelActual >= NIVEL_FINAL) {
            victoriaSolicitada = true;
            return;
        }

        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        nivelActual++;

        disposeNivel();
        initNivel();
        resize(w, h);
    }




    private void initOverlayOpciones() {
        pauseStage = new Stage(new ScreenViewport());
        skin = new Skin(Gdx.files.internal("uiskin.json"));

        // Fondo semitransparente
        Table fondo = new Table();
        fondo.setFillParent(true);
        fondo.setBackground(skin.newDrawable("white", 0, 0, 0, 0.6f));
        fondo.center();

        // Panel reutilizable
        OpcionesPanel panel = new OpcionesPanel(game, skin);

        // Botón cerrar
        TextButton cerrar = new TextButton("Cerrar", skin);
        cerrar.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override
            public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                cerrarOpciones();
            }
        });

        Table contenedor = new Table();
        contenedor.add(panel.getRoot()).padBottom(20).row();
        contenedor.add(cerrar).width(220).height(55);

        fondo.add(contenedor);

        pauseStage.addActor(fondo);
    }

    private void abrirOpciones() {
        if (opcionesAbiertas) return;
        opcionesAbiertas = true;

        // guardar el que estaba activo (por ejemplo el del juego)
        inputAnterior = Gdx.input.getInputProcessor();

        // bloquear movimiento
        ControlJugador.setPausa(true);

        // ahora la UI recibe input
        Gdx.input.setInputProcessor(pauseStage);
    }


    private void cerrarOpciones() {
        opcionesAbiertas = false;

        ControlJugador.setPausa(false);

        // restaurar el input anterior
        if (inputAnterior != null) {
            Gdx.input.setInputProcessor(inputAnterior);
        } else {
            // fallback: si tu juego usa stage, ponelo acá
            Gdx.input.setInputProcessor(null);
        }
    }




    // ==========================
    // LIFECYCLE
    // ==========================

    public void resize(int width, int height) {
        if (camaraSala != null) camaraSala.getViewport().update(width, height, true);
        if (hud != null) hud.resize(width, height);
        if (pauseStage != null) pauseStage.getViewport().update(width, height, true);
    }

    public void dispose() {
        if (opcionesAbiertas) cerrarOpciones();
        if (mapaRenderer != null) mapaRenderer.dispose();
        if (mapaTiled != null) mapaTiled.dispose();
        if (batch != null) batch.dispose();
        if (shapeRendererMundo != null) shapeRendererMundo.dispose();
        if (fisica != null) fisica.dispose();
        if (hud != null) hud.dispose();

        if (pauseStage != null) { pauseStage.dispose(); pauseStage = null; }
        if (skin != null) { skin.dispose(); skin = null; }

        if (sistemaSprites != null) {
            sistemaSprites.dispose();
        }

        if (texBotonRojo != null) texBotonRojo.dispose();
        if (texBotonAzul != null) texBotonAzul.dispose();

        if (texPuertaAbierta != null) texPuertaAbierta.dispose();
        if (texPuertaCerrada != null) texPuertaCerrada.dispose();

        if (texTrampilla != null) texTrampilla.dispose();

        for (Texture t : texturasItems.values()) {
            if (t != null) t.dispose();
        }
        texturasItems.clear();
        spritesItems.clear();


        resetearEstadoNivel();

        world = null;

    }

    private void disposeNivel() {
        // OJO: esto es nivel, NO run
        if (mapaRenderer != null) { mapaRenderer.dispose(); mapaRenderer = null; }
        if (mapaTiled != null) { mapaTiled.dispose(); mapaTiled = null; }

        if (batch != null) { batch.dispose(); batch = null; }
        if (shapeRendererMundo != null) { shapeRendererMundo.dispose(); shapeRendererMundo = null; }

        if (fisica != null) { fisica.dispose(); fisica = null; }
        world = null;

        if (hud != null) { hud.dispose(); hud = null; }

        if (sistemaSprites != null) { sistemaSprites.dispose(); sistemaSprites = null; }
        if (sistemaActualizacion != null) { sistemaActualizacion = null; }
        if (canalRenderizado != null) { canalRenderizado = null; }

        // Texturas/sprites del nivel
        if (texBotonRojo != null) { texBotonRojo.dispose(); texBotonRojo = null; }
        if (texBotonAzul != null) { texBotonAzul.dispose(); texBotonAzul = null; }

        if (texPuertaAbierta != null) { texPuertaAbierta.dispose(); texPuertaAbierta = null; }
        if (texPuertaCerrada != null) { texPuertaCerrada.dispose(); texPuertaCerrada = null; }

        if (texTrampilla != null) { texTrampilla.dispose(); texTrampilla = null; }

        for (Texture t : texturasItems.values()) {
            if (t != null) t.dispose();
        }
        texturasItems.clear();
        spritesItems.clear();

        // Colecciones de nivel
        resetearEstadoNivel();
    }



    private void sincronizarEstadoPuertasVisuales() {
        if (controlPuzzle == null || salaActual == null) return;

        boolean bloqueada = controlPuzzle.estaBloqueada(salaActual);

        // Si registraste puertas por sala en gestorEntidades, esto es lo ideal:
        if (gestorEntidades != null) {
            List<PuertaVisual> puertasSala = gestorEntidades.getPuertasVisuales(salaActual);
            for (PuertaVisual pv : puertasSala) {
                if (pv == null) continue;
                pv.setAbierta(!bloqueada);
            }
            return;
        }

        // Fallback (si no tenés el getter todavía): afecta a todas (menos ideal)
        for (PuertaVisual pv : puertasVisuales) {
            if (pv == null) continue;
            pv.setAbierta(!bloqueada);
        }
    }

    private void registrarSpritesItemsNuevos() {
        for (Item item : gestorEntidades.getItemsMundo()) {
            if (item == null) continue;

            if (sistemaSprites.tieneItemRegistrado(item)) continue;

            TextureRegion region = spritesItems.get(item.getTipo());
            if (region == null) continue;

            sistemaSprites.registrarItem(
                item,
                region,
                16f,   // ancho
                16f,   // alto
                0f,
                0f
            );
        }
    }

    public void notificarGameOver() {
        gameOverSolicitado = true;
    }

    public boolean consumirGameOverSolicitado() {
        if (!gameOverSolicitado) return false;
        gameOverSolicitado = false;
        return true;
    }

    private void actualizarGameOver(float delta) {
        if (gameOverSolicitado) return;

        // Tu regla: si muere 1 jugador => game over.
        boolean j1Muerto = (jugador1 != null && !jugador1.estaViva());
        boolean j2Muerto = (jugador2 != null && !jugador2.estaViva());

        if (j1Muerto || j2Muerto) {
            gameOverSolicitado = true;
        }
    }

    public boolean consumirVictoriaSolicitada() {
        if (!victoriaSolicitada) return false;
        victoriaSolicitada = false;
        return true;
    }





}
