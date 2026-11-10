package org.hp.cindermaw.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class CinderParticle extends SingleQuadParticle {
    public enum Style { EMBER, SMOKE, FLAME, CHUNK, FLASH }

    private final Style style;
    private final SpriteSet sprites;
    private final float baseSize;

    private CinderParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites, Style style, RandomSource random) {
        super(level, x, y, z, sprites.get(random));
        this.style = style;
        this.sprites = sprites;
        xd = vx;
        yd = vy;
        zd = vz;
        hasPhysics = false;
        switch (style) {
            case EMBER -> {
                lifetime = 14 + random.nextInt(18);
                baseSize = 0.045F + random.nextFloat() * 0.045F;
                gravity = -0.02F + random.nextFloat() * 0.06F;
                friction = 0.93F;
            }
            case SMOKE -> {
                lifetime = 24 + random.nextInt(18);
                baseSize = 0.16F + random.nextFloat() * 0.12F;
                gravity = -0.012F;
                friction = 0.9F;
                setSpriteFromAge(sprites);
            }
            case FLAME -> {
                lifetime = 8 + random.nextInt(6);
                baseSize = 0.11F + random.nextFloat() * 0.07F;
                gravity = -0.015F;
                friction = 0.9F;
                setSpriteFromAge(sprites);
            }
            case CHUNK -> {
                lifetime = 30 + random.nextInt(25);
                baseSize = 0.07F + random.nextFloat() * 0.06F;
                gravity = 1.0F;
                friction = 0.98F;
                hasPhysics = true;
            }
            default -> {
                lifetime = 5 + random.nextInt(3);
                baseSize = Math.min(0.55F, Math.max(0.28F, (float) vx * 0.14F));
                xd = 0;
                yd = 0;
                zd = 0;
                gravity = 0;
                friction = 1;
                setSpriteFromAge(sprites);
            }
        }
        quadSize = baseSize;
        if (style == Style.SMOKE) {
            float grey = 0.18F + random.nextFloat() * 0.08F;
            setColor(grey, grey * 0.92F, grey * 0.9F);
            alpha = 0.72F;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (removed) {
            return;
        }
        float t = (float) age / lifetime;
        switch (style) {
            case EMBER -> {
                xd += (random.nextFloat() - 0.5F) * 0.012F;
                zd += (random.nextFloat() - 0.5F) * 0.012F;
                setColor(1.0F, Mth.lerp(t, 0.95F, 0.25F), Mth.lerp(t, 0.6F, 0.05F));
                alpha = Math.min(1.0F, (1.0F - t) * 3.0F);
                quadSize = baseSize * (1.0F - t * 0.5F);
            }
            case SMOKE -> {
                setSpriteFromAge(sprites);
                alpha = 0.72F * (1.0F - t);
                quadSize = baseSize * (1.0F + t * 0.65F);
            }
            case FLAME -> {
                setSpriteFromAge(sprites);
                alpha = Math.min(1.0F, (1.0F - t) * 2.5F);
                quadSize = baseSize * (1.0F + Mth.sin(t * Mth.PI) * 0.22F);
            }
            case CHUNK -> alpha = Math.min(1.0F, (1.0F - t) * 4.0F);
            default -> {
                setSpriteFromAge(sprites);
                alpha = 1.0F - t;
                quadSize = baseSize * (1.0F - t * 0.35F);
            }
        }
    }

    @Override
    protected int getLightCoords(float partial) {
        if (style == Style.SMOKE || style == Style.CHUNK) {
            return super.getLightCoords(partial);
        }
        return 0xF000F0;
    }

    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final Style style;

        public Provider(SpriteSet sprites, Style style) {
            this.sprites = sprites;
            this.style = style;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz, RandomSource random) {
            return new CinderParticle(level, x, y, z, vx, vy, vz, sprites, style, random);
        }
    }
}
