package entidades.sprites;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import entidades.Entidad;
import entidades.personajes.Jugador;

public abstract class SpritesEntidad {

    protected final Entidad entidad;

    protected final int frameW;
    protected final int frameH;

    protected float offsetX = 0f;
    protected float offsetY = 0f;

    protected float anclaPie = 12f;

    protected float stateTime = 0f;

    protected Texture texQuieto;
    protected Texture texMovimiento;

    protected Texture texMuerte;
    protected boolean enMuerte = false;
    protected boolean muerteFinalizada = false;
    protected float muerteTime = 0f;

    protected Array<TextureRegion> framesQuieto;
    protected Array<TextureRegion> framesMovimiento;
    protected Array<TextureRegion> framesMuerte;

    protected Animation<TextureRegion> animQuieto;
    protected Animation<TextureRegion> animMovimiento;
    protected Animation<TextureRegion> animMuerte;

    protected TextureRegion fallbackQuieto;
    protected TextureRegion fallbackMovimiento;
    protected TextureRegion fallbackMuerte;

    protected boolean ultimaMiradaDerecha = true;

    protected SpritesEntidad(Entidad entidad, int frameW, int frameH) {
        this.entidad = entidad;
        this.frameW = frameW;
        this.frameH = frameH;
    }

    protected abstract String pathQuieto();
    protected abstract String pathMovimiento();
    protected String pathMuerte() { return null; }

    protected float duracionQuieto() { return 0.20f; }
    protected float duracionMovimiento() { return 0.12f; }
    protected float duracionMuerte() { return 0.10f; }

    protected void cargar() {
        texQuieto = new Texture(Gdx.files.internal(pathQuieto()));
        texMovimiento = new Texture(Gdx.files.internal(pathMovimiento()));

        String pm = pathMuerte();
        if (pm != null && !pm.isBlank()) {
            texMuerte = new Texture(Gdx.files.internal(pm));
        }
    }

    protected void construirAnimaciones() {
        framesQuieto = split(texQuieto);
        framesMovimiento = split(texMovimiento);

        animQuieto = new Animation<>(duracionQuieto(), framesQuieto, Animation.PlayMode.LOOP);
        animMovimiento = new Animation<>(duracionMovimiento(), framesMovimiento, Animation.PlayMode.LOOP);

        fallbackQuieto = framesQuieto.size > 0 ? framesQuieto.first() : new TextureRegion(texQuieto);
        fallbackMovimiento = framesMovimiento.size > 0 ? framesMovimiento.first() : new TextureRegion(texMovimiento);

        if (texMuerte != null) {
            framesMuerte = split(texMuerte);
            animMuerte = new Animation<>(duracionMuerte(), framesMuerte, Animation.PlayMode.NORMAL);
            fallbackMuerte = framesMuerte.size > 0 ? framesMuerte.first() : new TextureRegion(texMuerte);
        }
    }

    protected Array<TextureRegion> split(Texture tex) {
        Array<TextureRegion> frames = new Array<>();
        TextureRegion[][] grid = TextureRegion.split(tex, frameW, frameH);

        for (TextureRegion[] row : grid)
            for (TextureRegion r : row)
                frames.add(r);

        return frames;
    }

    public void setOffset(float x, float y) {
        this.offsetX = x;
        this.offsetY = y;
    }

    public void iniciarMuerte() {
        if (enMuerte) return;
        if (animMuerte == null || framesMuerte == null || framesMuerte.size == 0) return;

        enMuerte = true;
        muerteFinalizada = false;
        muerteTime = 0f;
    }


    public void detenerMuerte() {
        enMuerte = false;
        muerteFinalizada = false;
        muerteTime = 0f;
    }


    public boolean muerteTerminada() {
        return muerteFinalizada;
    }

    public boolean estaEnMuerte() {
        return enMuerte;
    }


    public void update(float delta) {
        stateTime += delta;

        if (enMuerte) {
            muerteTime += delta;
            float dur = animMuerte.getAnimationDuration();
            if (muerteTime >= dur) {
                muerteTime = dur; // clamp al último frame
                muerteFinalizada = true;
                // ❌ NO: enMuerte = false;
            }
        }
    }

    public void render(SpriteBatch batch) {
        if (entidad == null || entidad.getCuerpoFisico() == null) return;

        float vx = entidad.getCuerpoFisico().getLinearVelocity().x;

        if (Math.abs(vx) > 0.001f) {
            ultimaMiradaDerecha = vx >= 0f;
        }

        TextureRegion frame = elegirFrame();

        float w = frame.getRegionWidth();
        float h = frame.getRegionHeight();

        float x = entidad.getPosicion().x - w / 2f + offsetX;
        float y = entidad.getPosicion().y - anclaPie + offsetY;

        if (ultimaMiradaDerecha) {
            batch.draw(frame, x, y);
        } else {
            batch.draw(frame, x + w, y, -w, h);
        }
    }

    protected TextureRegion elegirFrame() {
        // 🔴 prioridad total: muerte
        if (enMuerte) {
            if (animMuerte != null && framesMuerte.size > 0) {
                return animMuerte.getKeyFrame(muerteTime, false);
            }
            return fallbackMuerte;
        }

        // 👇 recién acá miramos movimiento
        float v2 = entidad.getCuerpoFisico().getLinearVelocity().len2();
        boolean moviendo = v2 > 0.01f;

        if (moviendo) {
            return animMovimiento.getKeyFrame(stateTime, true);
        }
        return animQuieto.getKeyFrame(stateTime, true);
    }


    public void dispose() {
        if (texQuieto != null) texQuieto.dispose();
        if (texMovimiento != null) texMovimiento.dispose();
        if (texMuerte != null) texMuerte.dispose();
    }
}
