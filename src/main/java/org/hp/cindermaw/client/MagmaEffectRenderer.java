package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.entity.MagmaEffect;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

// 地面特效网格：冲击环、熔岩裂缝、喷泉火柱、熔岩池与新星火墙。
public final class MagmaEffectRenderer extends EntityRenderer<MagmaEffect> {
    public MagmaEffectRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(MagmaEffect effect) {
        return VfxDraw.ATLAS;
    }

    // 按效果范围放宽视锥裁剪
    @Override
    public boolean shouldRender(MagmaEffect effect, Frustum frustum, double x, double y, double z) {
        if (!effect.ready()) {
            return false;
        }
        double reach = effect.data().getFloat("R") + 3;
        if (effect.kind() == MagmaEffect.FISSURE) {
            reach = effect.data().getInt("Segments") + 3;
        }
        return effect.shouldRender(x, y, z) && frustum.isVisible(effect.getBoundingBox().inflate(reach, 5, reach));
    }

    @Override
    public void render(MagmaEffect effect, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!effect.ready()) {
            return;
        }
        float age = effect.age(partial);
        float time = effect.tickCount + partial;
        var d = effect.data();
        switch (effect.kind()) {
            case MagmaEffect.SHOCKWAVE -> shockwave(d.getFloat("R"), d.getInt("Style"), age, pose, buffers);
            case MagmaEffect.FISSURE -> fissure(effect, age, time, pose, buffers);
            case MagmaEffect.GEYSERS -> geysers(effect, age, time, pose, buffers);
            case MagmaEffect.POOL -> pool(effect, d.getFloat("R"), age, time, pose, buffers);
            case MagmaEffect.NOVA -> nova(effect, age, time, pose, buffers);
            default -> {
            }
        }
    }

    // 冲击环：先快后慢扩张，发光环带加翻起的烟尘墙与中心 3D 热浪穹顶
    private void shockwave(float radius, int style, float age, PoseStack pose, MultiBufferSource buffers) {
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
        VfxDraw.ring(buffers, VfxDraw.GLOW, pose, new Vec3(0, 0.08, 0), r, Math.max(0, r - 1.2 * fade - 0.2), 0.0, VfxDraw.RING, color, fade);
        VfxDraw.wall(buffers, pose, new Vec3(0, 0.04, 0), r * 0.96, (0.55 + radius * 0.06) * fade, -age * 0.18F, color, fade * 0.72F);
        VfxDraw.ring(buffers, VfxDraw.SOFT, pose, new Vec3(0, 0.05, 0), r * 0.98, Math.max(0, r * 0.98 - 0.45), 0.55 * fade, VfxDraw.DUST, 0x3A3236, fade * 0.8F);
        if (age < 7) {
            float dt = age / 7.0F;
            VfxDraw.dome(buffers, pose, new Vec3(0, 0.05, 0), 0.5 + radius * 0.52 * dt, -age * 0.25F, 0xFFF0A8, color, (1.0F - dt) * 0.85F);
        }
    }

    // 熔岩裂缝：逐段延伸的发光裂纹，喷发时每段冒出火柱
    private void fissure(MagmaEffect effect, float age, float time, PoseStack pose, MultiBufferSource buffers) {
        var d = effect.data();
        int lines = d.getInt("Lines");
        int segments = d.getInt("Segments");
        float reveal = segments * Math.min(1.0F, age / 10.0F);
        float fade = Math.min(1.0F, (effect.life() - age) / 12.0F);
        VertexConsumer glow = buffers.getBuffer(VfxDraw.GLOW);
        float[] cr = VfxDraw.CRACK;
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
                // 裂缝宽度随喷发前的蓄热变粗
                int erupt = MagmaEffect.fissureEruptAge(k);
                float heat = Mth.clamp((age - erupt + 6) / 6.0F, 0.0F, 1.0F);
                double width = 0.22 + 0.25 * heat;
                float partial = Math.min(1.0F, reveal - k);
                Vec3 end = a.add(along.scale(partial));
                VfxDraw.quad2(glow, pose, a.add(side.scale(width)), end.add(side.scale(width)), end.subtract(side.scale(width)), a.subtract(side.scale(width)),
                        cr[0], cr[1], cr[0] + (cr[2] - cr[0]) * partial, cr[3], 0xFF8030, fade);
                // 喷发火柱：迅速窜高后回落
                float since = age - erupt;
                if (since >= 0 && since < 12) {
                    float height = (float) (2.8 * Math.sin(Math.PI * since / 12.0F));
                    Vec3 mid = a.add(along.scale(0.5));
                    VfxDraw.column(buffers, pose, mid, new Vec3(0, height, 0), 0.45, 0.15, 3, -time * 0.3F + k, 1.0F, 0xFF6A20, 0.9F);
                    VfxDraw.column(buffers, pose, mid, new Vec3(0, height * 0.6, 0), 0.25, 0.08, 2, -time * 0.35F + k, 1.0F, 0xFFE09A, 0.9F);
                }
            }
        }
    }

    // 喷泉：预警圈收拢，到时喷出巨型火柱
    private void geysers(MagmaEffect effect, float age, float time, PoseStack pose, MultiBufferSource buffers) {
        var d = effect.data();
        int delay = d.getInt("Delay");
        for (int i = 0; i < d.getInt("Count"); i++) {
            float dy = effect.groundOffset(i);
            if (Float.isNaN(dy)) {
                continue;
            }
            Vec3 at = effect.geyserPoint(i).add(0, dy + 0.05, 0);
            if (age < delay) {
                float t = age / delay;
                VfxDraw.flat(buffers, VfxDraw.GLOW, pose, at, 1.2, VfxDraw.WARN, 0xFF5020, 0.4F + 0.6F * t);
                VfxDraw.flat(buffers, VfxDraw.GLOW, pose, at.add(0, 0.01, 0), 1.2 * (1 - t) + 0.15, VfxDraw.WARN, 0xFFC070, 0.8F);
                continue;
            }
            float since = age - delay;
            if (since < 18) {
                float rise = Math.min(1.0F, since / 2.5F);
                float decay = Math.min(1.0F, (18 - since) / 7.0F);
                double height = (4.8 + Mth.sin(time * 1.9F + i) * 0.4) * rise * decay;
                VfxDraw.column(buffers, pose, at, new Vec3(0, height, 0), 0.72, 0.28, 3, -time * 0.32F + i, 1.6F, 0xFF5A1A, 0.95F * decay);
                VfxDraw.column(buffers, pose, at, new Vec3(0, height * 0.68, 0), 0.38, 0.14, 3, -time * 0.42F + i, 1.2F, 0xFFF0A8, 0.95F * decay);
                if (since < 8) {
                    float bt = since / 8.0F;
                    VfxDraw.dome(buffers, pose, at, 0.55 + bt * 1.15, -time * 0.3F + i, 0xFFE898, 0xD83810, (1.0F - bt) * 0.85F);
                }
                VfxDraw.ring(buffers, VfxDraw.GLOW, pose, at.add(0, 0.03, 0), 1.25, 0.35, 0.0, VfxDraw.RING, 0xFF8A30, decay * 0.85F);
            }
        }
    }

    // 熔岩池：不规则多边形，先是流动熔浆，再冷却成黑壳与发光裂缝，外圈留焦痕
    private void pool(MagmaEffect effect, float radius, float age, float time, PoseStack pose, MultiBufferSource buffers) {
        float grow = 1.0F - (1.0F - Math.min(1.0F, age / 5.0F)) * (1.0F - Math.min(1.0F, age / 5.0F));
        float fade = Math.min(1.0F, (effect.life() - age) / 18.0F);
        float cool = Math.max(0.0F, Math.min(1.0F, (age - 18.0F) / 35.0F));
        int seed = effect.getId();
        // 每个池子错开一点高度，重叠时不闪烁
        double lift = 0.03 + (seed % 7) * 0.004;
        // 不规则边缘：随机半径再与相邻点平滑
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
        Matrix4f m = pose.last().pose();
        Matrix3f nm = pose.last().normal();
        // 世界坐标平铺，避免每个池子贴图一样
        double ox = effect.getX() * 0.4;
        double oz = effect.getZ() * 0.4;
        float flow = time * 0.004F;
        int moltenRgb = VfxDraw.mix(0xFFFFFF, 0x6A1608, cool);
        float moltenAlpha = (1.0F - cool * 0.9F) * fade;
        float crackAlpha = cool * fade * (0.75F + Mth.sin(time * 0.15F + seed) * 0.25F);
        VertexConsumer scorch = buffers.getBuffer(VfxDraw.SOFT);
        VertexConsumer molten = buffers.getBuffer(VfxDraw.MOLTEN);
        VertexConsumer crust = buffers.getBuffer(VfxDraw.CRUST);
        float[] sr = VfxDraw.SCORCH;
        Vec3 c = new Vec3(0, lift, 0);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double a0 = i * Math.PI * 2 / n;
            double a1 = j * Math.PI * 2 / n;
            Vec3 p0 = new Vec3(Math.cos(a0) * r * edge[i], lift, Math.sin(a0) * r * edge[i]);
            Vec3 p1 = new Vec3(Math.cos(a1) * r * edge[j], lift, Math.sin(a1) * r * edge[j]);
            // 焦痕：比熔岩大一圈，冷却后更明显
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
            scorchVertex(scorch, m, nm, c.add(0, -0.008, 0), scu, scv, scorchAlpha);
            scorchVertex(scorch, m, nm, s1, su1, sv1, scorchAlpha);
            scorchVertex(scorch, m, nm, s0, su0, sv0, scorchAlpha);
            scorchVertex(scorch, m, nm, c.add(0, -0.008, 0), scu, scv, scorchAlpha);
            // 熔浆层：中心亮、边缘暗，贴图缓慢流动
            float cu = (float) ox + flow;
            float cv = (float) oz;
            VfxDraw.vertex(molten, m, nm, c, cu, cv, moltenRgb, moltenAlpha);
            VfxDraw.vertex(molten, m, nm, p1, (float) (ox + p1.x * 0.4) + flow, (float) (oz + p1.z * 0.4), moltenRgb, moltenAlpha * 0.55F);
            VfxDraw.vertex(molten, m, nm, p0, (float) (ox + p0.x * 0.4) + flow, (float) (oz + p0.z * 0.4), moltenRgb, moltenAlpha * 0.55F);
            VfxDraw.vertex(molten, m, nm, c, cu, cv, moltenRgb, moltenAlpha);
            // 冷却裂缝层：黑壳之间透出暗橙光
            if (crackAlpha > 0.01F) {
                Vec3 q0 = p0.add(0, 0.004, 0);
                Vec3 q1 = p1.add(0, 0.004, 0);
                Vec3 qc = c.add(0, 0.004, 0);
                VfxDraw.vertex(crust, m, nm, qc, (float) ox, (float) oz, 0xFF9A50, crackAlpha);
                VfxDraw.vertex(crust, m, nm, q1, (float) (ox + q1.x * 0.4), (float) (oz + q1.z * 0.4), 0xFF9A50, crackAlpha * 0.4F);
                VfxDraw.vertex(crust, m, nm, q0, (float) (ox + q0.x * 0.4), (float) (oz + q0.z * 0.4), 0xFF9A50, crackAlpha * 0.4F);
                VfxDraw.vertex(crust, m, nm, qc, (float) ox, (float) oz, 0xFF9A50, crackAlpha);
            }
        }
    }

    // 焦痕顶点：半透明层需要真实透明度，不预乘颜色
    private static void scorchVertex(VertexConsumer out, Matrix4f m, Matrix3f n, Vec3 p, float u, float v, float alpha) {
        out.vertex(m, (float) p.x, (float) p.y, (float) p.z)
                .color(26, 16, 12, (int) (255 * Math.max(0, Math.min(1, alpha))))
                .uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0)
                .normal(n, 0, 1, 0).endVertex();
    }

    // 新星：火墙圆环向外推进，地面留下发光带
    private void nova(MagmaEffect effect, float age, float time, PoseStack pose, MultiBufferSource buffers) {
        float radius = effect.novaRadius(age);
        float max = effect.data().getFloat("R");
        if (radius > max + 1) {
            return;
        }
        float fade = Math.min(1.0F, (max + 1 - radius) / 3.0F);
        VfxDraw.wall(buffers, pose, new Vec3(0, 0.02, 0), radius, 1.25 * fade, -time * 0.22F, 0xFF6A22, 0.95F * fade);
        VfxDraw.wall(buffers, pose, new Vec3(0, 0.02, 0), radius - 0.12, 0.7 * fade, -time * 0.28F, 0xFFE0A0, 0.8F * fade);
        VfxDraw.ring(buffers, VfxDraw.GLOW, pose, new Vec3(0, 0.06, 0), radius + 0.2, Math.max(0, radius - 1.6), 0.0, VfxDraw.RING, 0xFF7A2A, 0.8F * fade);
    }
}
