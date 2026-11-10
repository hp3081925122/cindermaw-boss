package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.entity.MagmaEffect;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class MagmaEffectRenderer extends EntityRenderer<MagmaEffect, MagmaEffectRenderer.State> {
    public MagmaEffectRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MagmaEffect effect, State state, float partial) {
        super.extractRenderState(effect, state, partial);
        state.effect = effect;
        state.ready = effect.ready();
        if (!state.ready) {
            return;
        }
        state.age = effect.age(partial);
        state.time = effect.tickCount + partial;
        state.data = effect.data().copy();
        state.kind = effect.kind();
        state.life = effect.life();
        state.id = effect.getId();
    }

    @Override
    public boolean shouldRender(MagmaEffect effect, Frustum frustum, double x, double y, double z) {
        if (!effect.ready()) {
            return false;
        }
        double reach = effect.data().getFloatOr("R", 0.0F) + 3;
        if (effect.kind() == MagmaEffect.FISSURE) {
            reach = effect.data().getIntOr("Segments", 0) + 3;
        }
        return effect.shouldRender(x, y, z) && frustum.isVisible(effect.getBoundingBox().inflate(reach, 5, reach));
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.ready || state.effect == null) {
            return;
        }
        MagmaEffect effect = state.effect;
        switch (state.kind) {
            case MagmaEffect.SHOCKWAVE -> shockwave(state.data.getFloatOr("R", 0.0F), state.data.getIntOr("Style", 0), state.age, pose, collector);
            case MagmaEffect.FISSURE -> fissure(effect, state.age, state.time, pose, collector);
            case MagmaEffect.GEYSERS -> geysers(effect, state.age, state.time, pose, collector);
            case MagmaEffect.POOL -> pool(effect, state.data.getFloatOr("R", 0.0F), state.age, state.time, pose, collector);
            case MagmaEffect.NOVA -> nova(effect, state.age, state.time, pose, collector);
            default -> {
            }
        }
    }

    private void shockwave(float radius, int style, float age, PoseStack pose, SubmitNodeCollector collector) {
        if (age > 16) {
            return;
        }
        float t = Math.min(1.0F, age / 12.0F);
        float ease = 1 - (1 - t) * (1 - t);
        float r = 0.4F + radius * ease;
        float fade = 1 - t;
        int color = 0xFF7A2A;
        if (style == 1) {
            color = 0xFFC060;
        }
        int glow = color;
        VfxDraw.submit(collector, pose, VfxDraw.GLOW, (local, out) ->
                VfxDraw.ring(out, local, new Vec3(0, 0.08, 0), r, Math.max(0, r - 1.2 * fade - 0.2), 0.0, VfxDraw.RING, glow, fade));
        VfxDraw.submit(collector, pose, VfxDraw.FLAME, (local, out) -> {
            VfxDraw.wall(out, local, new Vec3(0, 0.04, 0), r * 0.96, (0.55 + radius * 0.06) * fade, -age * 0.18F, glow, fade * 0.72F);
            if (age < 7) {
                float dt = age / 7.0F;
                VfxDraw.dome(out, local, new Vec3(0, 0.05, 0), 0.5 + radius * 0.52 * dt, -age * 0.25F, 0xFFF0A8, glow, (1.0F - dt) * 0.85F);
            }
        });
        VfxDraw.submit(collector, pose, VfxDraw.SOFT, (local, out) ->
                VfxDraw.ring(out, local, new Vec3(0, 0.05, 0), r * 0.98, Math.max(0, r * 0.98 - 0.45), 0.55 * fade, VfxDraw.DUST, 0x3A3236, fade * 0.8F));
    }

    private void fissure(MagmaEffect effect, float age, float time, PoseStack pose, SubmitNodeCollector collector) {
        CompoundTag d = effect.data();
        int lines = d.getIntOr("Lines", 0);
        int segments = d.getIntOr("Segments", 0);
        float reveal = segments * Math.min(1.0F, age / 10.0F);
        float fade = Math.min(1.0F, (effect.life() - age) / 12.0F);
        float[] cr = VfxDraw.CRACK;
        VfxDraw.submit(collector, pose, VfxDraw.GLOW, (local, out) -> {
            for (int line = 0; line < lines; line++) {
                for (int k = 0; k < segments; k++) {
                    float dy = effect.groundOffset(line * segments + k);
                    if (Float.isNaN(dy) || k >= reveal) {
                        break;
                    }
                    Vec3 a = effect.fissurePoint(line, k).add(0, dy + 0.04, 0);
                    Vec3 b = effect.fissurePoint(line, k + 1).add(0, dy + 0.04, 0);
                    Vec3 along = b.subtract(a);
                    Vec3 side = new Vec3(-along.z, 0, along.x).normalize();
                    int erupt = MagmaEffect.fissureEruptAge(k);
                    float heat = Mth.clamp((age - erupt + 6) / 6.0F, 0.0F, 1.0F);
                    double width = 0.22 + 0.25 * heat;
                    float partial = Math.min(1.0F, reveal - k);
                    Vec3 end = a.add(along.scale(partial));
                    VfxDraw.quad2(out, local, a.add(side.scale(width)), end.add(side.scale(width)), end.subtract(side.scale(width)), a.subtract(side.scale(width)),
                            cr[0], cr[1], cr[0] + (cr[2] - cr[0]) * partial, cr[3], 0xFF8030, fade);
                }
            }
        });
        for (int line = 0; line < lines; line++) {
            for (int k = 0; k < segments; k++) {
                float dy = effect.groundOffset(line * segments + k);
                if (Float.isNaN(dy) || k >= reveal) {
                    break;
                }
                Vec3 a = effect.fissurePoint(line, k).add(0, dy + 0.04, 0);
                Vec3 b = effect.fissurePoint(line, k + 1).add(0, dy + 0.04, 0);
                Vec3 along = b.subtract(a);
                int erupt = MagmaEffect.fissureEruptAge(k);
                float since = age - erupt;
                if (since >= 0 && since < 12) {
                    float height = (float) (2.8 * Math.sin(Math.PI * since / 12.0F));
                    Vec3 mid = a.add(along.scale(0.5));
                    VfxDraw.column(collector, pose, mid, new Vec3(0, height, 0), 0.45, 0.15, 3, -time * 0.3F + k, 1.0F, 0xFF6A20, 0.9F);
                    VfxDraw.column(collector, pose, mid, new Vec3(0, height * 0.6, 0), 0.25, 0.08, 2, -time * 0.35F + k, 1.0F, 0xFFE09A, 0.9F);
                }
            }
        }
    }

    private void geysers(MagmaEffect effect, float age, float time, PoseStack pose, SubmitNodeCollector collector) {
        CompoundTag d = effect.data();
        int delay = d.getIntOr("Delay", 0);
        int count = d.getIntOr("Count", 0);
        for (int i = 0; i < count; i++) {
            float dy = effect.groundOffset(i);
            if (Float.isNaN(dy)) {
                continue;
            }
            Vec3 at = effect.geyserPoint(i).add(0, dy + 0.05, 0);
            if (age < delay) {
                float t = age / delay;
                VfxDraw.submit(collector, pose, VfxDraw.GLOW, (local, out) -> {
                    VfxDraw.flat(out, local, at, 1.2, VfxDraw.WARN, 0xFF5020, 0.4F + 0.6F * t);
                    VfxDraw.flat(out, local, at.add(0, 0.01, 0), 1.2 * (1 - t) + 0.15, VfxDraw.WARN, 0xFFC070, 0.8F);
                });
                continue;
            }
            float since = age - delay;
            if (since < 18) {
                float rise = Math.min(1.0F, since / 2.5F);
                float decay = Math.min(1.0F, (18 - since) / 7.0F);
                double height = (4.8 + Mth.sin(time * 1.9F + i) * 0.4) * rise * decay;
                VfxDraw.column(collector, pose, at, new Vec3(0, height, 0), 0.72, 0.28, 3, -time * 0.32F + i, 1.6F, 0xFF5A1A, 0.95F * decay);
                VfxDraw.column(collector, pose, at, new Vec3(0, height * 0.68, 0), 0.38, 0.14, 3, -time * 0.42F + i, 1.2F, 0xFFF0A8, 0.95F * decay);
                if (since < 8) {
                    float bt = since / 8.0F;
                    VfxDraw.dome(collector, pose, at, 0.55 + bt * 1.15, -time * 0.3F + i, 0xFFE898, 0xD83810, (1.0F - bt) * 0.85F);
                }
                VfxDraw.submit(collector, pose, VfxDraw.GLOW, (local, out) ->
                        VfxDraw.ring(out, local, at.add(0, 0.03, 0), 1.25, 0.35, 0.0, VfxDraw.RING, 0xFF8A30, decay * 0.85F));
            }
        }
    }

    private void pool(MagmaEffect effect, float radius, float age, float time, PoseStack pose, SubmitNodeCollector collector) {
        float grow = 1.0F - (1.0F - Math.min(1.0F, age / 5.0F)) * (1.0F - Math.min(1.0F, age / 5.0F));
        float fade = Math.min(1.0F, (effect.life() - age) / 18.0F);
        float cool = Math.max(0.0F, Math.min(1.0F, (age - 18.0F) / 35.0F));
        int seed = effect.getId();
        double lift = 0.03 + (seed % 7) * 0.004;
        int n = 22;
        float[] raw = new float[n];
        for (int i = 0; i < n; i++) {
            float h = Mth.sin(seed * 12.9898F + i * 78.233F) * 43758.547F;
            raw[i] = 0.72F + (h - Mth.floor(h)) * 0.45F;
        }
        float[] edge = new float[n];
        for (int i = 0; i < n; i++) {
            edge[i] = (raw[(i + n - 1) % n] + raw[i] * 2 + raw[(i + 1) % n]) / 4.0F;
        }
        float r = radius * grow;
        double ox = effect.getX() * 0.4;
        double oz = effect.getZ() * 0.4;
        float flow = time * 0.004F;
        int moltenRgb = VfxDraw.mix(0xFFFFFF, 0x6A1608, cool);
        float moltenAlpha = (1.0F - cool * 0.9F) * fade;
        float crackAlpha = cool * fade * (0.75F + Mth.sin(time * 0.15F + seed) * 0.25F);
        float[] sr = VfxDraw.SCORCH;
        Vec3 c = new Vec3(0, lift, 0);
        VfxDraw.submit(collector, pose, VfxDraw.SOFT, (local, out) -> {
            Matrix4f m = local.last().pose();
            Matrix3f nm = local.last().normal();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double a0 = i * Math.PI * 2 / n;
                double a1 = j * Math.PI * 2 / n;
                Vec3 p0 = new Vec3(Math.cos(a0) * r * edge[i], lift, Math.sin(a0) * r * edge[i]);
                Vec3 p1 = new Vec3(Math.cos(a1) * r * edge[j], lift, Math.sin(a1) * r * edge[j]);
                Vec3 s0 = new Vec3(p0.x * 1.35, lift - 0.008, p0.z * 1.35);
                Vec3 s1 = new Vec3(p1.x * 1.35, lift - 0.008, p1.z * 1.35);
                double span = r * 1.35 * 1.2;
                float su0 = (float) (sr[0] + (sr[2] - sr[0]) * (0.5 + s0.x / span / 2));
                float sv0 = (float) (sr[1] + (sr[3] - sr[1]) * (0.5 + s0.z / span / 2));
                float su1 = (float) (sr[0] + (sr[2] - sr[0]) * (0.5 + s1.x / span / 2));
                float sv1 = (float) (sr[1] + (sr[3] - sr[1]) * (0.5 + s1.z / span / 2));
                float scu = (sr[0] + sr[2]) / 2;
                float scv = (sr[1] + sr[3]) / 2;
                float scorchAlpha = (0.55F + cool * 0.35F) * fade * grow;
                scorchVertex(out, m, nm, c.add(0, -0.008, 0), scu, scv, scorchAlpha);
                scorchVertex(out, m, nm, s1, su1, sv1, scorchAlpha);
                scorchVertex(out, m, nm, s0, su0, sv0, scorchAlpha);
                scorchVertex(out, m, nm, c.add(0, -0.008, 0), scu, scv, scorchAlpha);
            }
        });
        VfxDraw.submit(collector, pose, VfxDraw.MOLTEN, (local, out) -> {
            Matrix4f m = local.last().pose();
            Matrix3f nm = local.last().normal();
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double a0 = i * Math.PI * 2 / n;
                double a1 = j * Math.PI * 2 / n;
                Vec3 p0 = new Vec3(Math.cos(a0) * r * edge[i], lift, Math.sin(a0) * r * edge[i]);
                Vec3 p1 = new Vec3(Math.cos(a1) * r * edge[j], lift, Math.sin(a1) * r * edge[j]);
                float cu = (float) ox + flow;
                float cv = (float) oz;
                VfxDraw.vertex(out, m, nm, c, cu, cv, moltenRgb, moltenAlpha);
                VfxDraw.vertex(out, m, nm, p1, (float) (ox + p1.x * 0.4) + flow, (float) (oz + p1.z * 0.4), moltenRgb, moltenAlpha * 0.55F);
                VfxDraw.vertex(out, m, nm, p0, (float) (ox + p0.x * 0.4) + flow, (float) (oz + p0.z * 0.4), moltenRgb, moltenAlpha * 0.55F);
                VfxDraw.vertex(out, m, nm, c, cu, cv, moltenRgb, moltenAlpha);
            }
        });
        if (crackAlpha > 0.01F) {
            VfxDraw.submit(collector, pose, VfxDraw.CRUST, (local, out) -> {
                Matrix4f m = local.last().pose();
                Matrix3f nm = local.last().normal();
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    double a0 = i * Math.PI * 2 / n;
                    double a1 = j * Math.PI * 2 / n;
                    Vec3 p0 = new Vec3(Math.cos(a0) * r * edge[i], lift, Math.sin(a0) * r * edge[i]);
                    Vec3 p1 = new Vec3(Math.cos(a1) * r * edge[j], lift, Math.sin(a1) * r * edge[j]);
                    Vec3 q0 = p0.add(0, 0.004, 0);
                    Vec3 q1 = p1.add(0, 0.004, 0);
                    Vec3 qc = c.add(0, 0.004, 0);
                    VfxDraw.vertex(out, m, nm, qc, (float) ox, (float) oz, 0xFF9A50, crackAlpha);
                    VfxDraw.vertex(out, m, nm, q1, (float) (ox + q1.x * 0.4), (float) (oz + q1.z * 0.4), 0xFF9A50, crackAlpha * 0.4F);
                    VfxDraw.vertex(out, m, nm, q0, (float) (ox + q0.x * 0.4), (float) (oz + q0.z * 0.4), 0xFF9A50, crackAlpha * 0.4F);
                    VfxDraw.vertex(out, m, nm, qc, (float) ox, (float) oz, 0xFF9A50, crackAlpha);
                }
            });
        }
    }

    private static void scorchVertex(VertexConsumer out, Matrix4f m, Matrix3f n, Vec3 p, float u, float v, float alpha) {
        out.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
                .setColor(26, 16, 12, (int) (255 * Math.max(0, Math.min(1, alpha))))
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
                .setNormal(0, 1, 0);
    }

    private void nova(MagmaEffect effect, float age, float time, PoseStack pose, SubmitNodeCollector collector) {
        float radius = effect.novaRadius(age);
        float max = effect.data().getFloatOr("R", 0.0F);
        if (radius > max + 1) {
            return;
        }
        float fade = Math.min(1.0F, (max + 1 - radius) / 3.0F);
        VfxDraw.submit(collector, pose, VfxDraw.FLAME, (local, out) -> {
            VfxDraw.wall(out, local, new Vec3(0, 0.02, 0), radius, 1.25 * fade, -time * 0.22F, 0xFF6A22, 0.95F * fade);
            VfxDraw.wall(out, local, new Vec3(0, 0.02, 0), radius - 0.12, 0.7 * fade, -time * 0.28F, 0xFFE0A0, 0.8F * fade);
        });
        VfxDraw.submit(collector, pose, VfxDraw.GLOW, (local, out) ->
                VfxDraw.ring(out, local, new Vec3(0, 0.06, 0), radius + 0.2, Math.max(0, radius - 1.6), 0.0, VfxDraw.RING, 0xFF7A2A, 0.8F * fade));
    }

    public static final class State extends EntityRenderState {
        public MagmaEffect effect;
        public boolean ready;
        public float age;
        public float time;
        public CompoundTag data = new CompoundTag();
        public int kind;
        public int life;
        public int id;
    }
}
