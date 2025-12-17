package server;

import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.*;

import fisica.ColisionesDesdeTiled;
import interfaces.GameController;

public class GameControllerImpl implements GameController {

    private ServerThread server;

    private World world;
    private Body b1, b2;

    private final int[] dx = new int[3];
    private final int[] dy = new int[3];

    private volatile boolean running = false;

    // ✅ map pre-cargado en hilo GL
    private TiledMap map;

    public void setServer(ServerThread server) {
        this.server = server;
    }

    // ✅ nuevo
    public void setTiledMap(TiledMap map) {
        this.map = map;
    }

    @Override
    public void startGame() {
        System.out.println("[SERVER] Juego iniciado");

        // ✅ NO cargar TMX acá.
        // Solo inicializar física usando el map ya cargado.
        initFisicaServidor();

        enviarPosiciones();
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
        enviarPosiciones();
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
            case NORTE: cy += off; break; // arriba
            case SUR:   cy -= off; break; // abajo
            case ESTE:  cx += off; break; // derecha
            case OESTE: cx -= off; break; // izquierda
        }


        float sep = 24f;

        if (b1 != null) { b1.setTransform(cx - sep, cy, b1.getAngle()); b1.setLinearVelocity(0,0); }
        if (b2 != null) { b2.setTransform(cx + sep, cy, b2.getAngle()); b2.setLinearVelocity(0,0); }

        enviarPosiciones();
    }

    private void startLoop() {
        if (running) return;
        running = true;

        new Thread(() -> {
            final float DT = 1f / 60f;

            while (running) {
                aplicarInputServidor(160f);
                world.step(DT, 6, 2);
                enviarPosiciones();

                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }, "ServerPhysicsLoop").start();
    }

    private void aplicarInputServidor(float speed) {
        aplicarVelocidad(b1, dx[1], dy[1], speed);
        aplicarVelocidad(b2, dx[2], dy[2], speed);
    }

    private void aplicarVelocidad(Body b, int dx, int dy, float speed) {
        if (b == null) return;
        Vector2 v = new Vector2(dx, dy);
        if (v.len2() > 1f) v.nor();
        v.scl(speed);
        b.setLinearVelocity(v);
    }

    private void enviarPosiciones() {
        if (server == null) return;

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
