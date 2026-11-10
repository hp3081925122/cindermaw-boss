package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.Cindermaw;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// 网格特效绘制工具：叠加发光层画火焰与裂缝，半透明层画熔岩池与岩块。
public final class VfxDraw {
    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/effect/vfx.png");
    public static final ResourceLocation FLAME_SCROLL = ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/effect/flame_scroll.png");
    public static final RenderType GLOW = RenderType.eyes(ATLAS);
    public static final RenderType SOFT = RenderType.entityTranslucentEmissive(ATLAS);
    public static final RenderType FLAME = RenderType.eyes(FLAME_SCROLL);
    // 熔岩池两张可平铺贴图：流动熔浆与冷却裂缝
    public static final RenderType MOLTEN = RenderType.eyes(ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/effect/lava_molten.png"));
    public static final RenderType CRUST = RenderType.eyes(ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/effect/lava_crust.png"));

    // 图集分区（128×128 像素）
    public static final float[] CRACK = region(0, 0, 64, 8);
    public static final float[] RING = region(64, 0, 128, 8);
    public static final float[] POOL = region(32, 8, 64, 40);
    public static final float[] WARN = region(64, 8, 96, 40);
    public static final float[] DISC = region(96, 8, 128, 40);
    public static final float[] TRAIL = region(0, 40, 64, 48);
    public static final float[] ROCK = region(64, 40, 80, 56);
    public static final float[] CORE = region(80, 40, 96, 56);
    public static final float[] DUST = region(0, 8, 32, 40);
    // 喷焰火团两种变体
    // 焦痕：白色径向透明度，染色后用作烧焦地面
    public static final float[] SCORCH = region(0, 88, 32, 120);
    public static final float[][] PUFF = {region(0, 56, 32, 88), region(32, 56, 64, 88)};

    private VfxDraw() {
    }

    private static float[] region(int x0, int y0, int x1, int y1) {
        return new float[]{x0 / 128F, y0 / 128F, x1 / 128F, y1 / 128F};
    }

    // 单个顶点：全亮光照；叠加混合不读透明度，所以颜色预乘透明度来实现渐隐
    public static void vertex(VertexConsumer out, Matrix4f matrix, Matrix3f normal, Vec3 p, float u, float v, int rgb, float alpha) {
        float k = Math.max(0, Math.min(1, alpha));
        int a = (int) (255 * k);
        out.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .color((int) (((rgb >> 16) & 255) * k), (int) (((rgb >> 8) & 255) * k), (int) ((rgb & 255) * k), a)
                .uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0)
                .normal(normal, 0, 1, 0).endVertex();
    }

    // 四边形：a-b 为 v0 边，d-c 为 v1 边
    public static void quad(VertexConsumer out, PoseStack pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                            float u0, float v0, float u1, float v1, int rgb, float alpha) {
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        vertex(out, m, n, a, u0, v0, rgb, alpha);
        vertex(out, m, n, b, u1, v0, rgb, alpha);
        vertex(out, m, n, c, u1, v1, rgb, alpha);
        vertex(out, m, n, d, u0, v1, rgb, alpha);
    }

    // 双面四边形：发光层不剔除背面时用于竖直面片
    public static void quad2(VertexConsumer out, PoseStack pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             float u0, float v0, float u1, float v1, int rgb, float alpha) {
        quad(out, pose, a, b, c, d, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, b, a, d, c, u1, v0, u0, v1, rgb, alpha);
    }

    // 支持上下边独立颜色与透明度的双面四边形，用于 3D 网格平滑羽化
    public static void quadGrad2(VertexConsumer out, PoseStack pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                                 float u0, float v0, float u1, float v1,
                                 int rgbTop, float alphaTop, int rgbBot, float alphaBot) {
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        vertex(out, m, n, a, u0, v0, rgbTop, alphaTop);
        vertex(out, m, n, b, u1, v0, rgbTop, alphaTop);
        vertex(out, m, n, c, u1, v1, rgbBot, alphaBot);
        vertex(out, m, n, d, u0, v1, rgbBot, alphaBot);

        vertex(out, m, n, b, u1, v0, rgbTop, alphaTop);
        vertex(out, m, n, a, u0, v0, rgbTop, alphaTop);
        vertex(out, m, n, d, u0, v1, rgbBot, alphaBot);
        vertex(out, m, n, c, u1, v1, rgbBot, alphaBot);
    }

    // 贴地水平方片：以中心为准的图集区域
    public static void flat(MultiBufferSource buffers, RenderType type, PoseStack pose, Vec3 center, double radius, float[] r, int rgb, float alpha) {
        VertexConsumer out = buffers.getBuffer(type);
        Vec3 a = center.add(-radius, 0, -radius);
        Vec3 b = center.add(radius, 0, -radius);
        Vec3 c = center.add(radius, 0, radius);
        Vec3 d = center.add(-radius, 0, radius);
        quad2(out, pose, a, b, c, d, r[0], r[1], r[2], r[3], rgb, alpha);
    }

    // 贴地圆环带：u 沿圆周重复图集区域，v 由外到内
    public static void ring(MultiBufferSource buffers, RenderType type, PoseStack pose, Vec3 center, double outer, double inner,
                            double lift, float[] r, int rgb, float alpha) {
        VertexConsumer out = buffers.getBuffer(type);
        int segments = Math.max(20, (int) (outer * 12));
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            Vec3 o0 = center.add(Math.cos(a0) * outer, 0, Math.sin(a0) * outer);
            Vec3 o1 = center.add(Math.cos(a1) * outer, 0, Math.sin(a1) * outer);
            Vec3 i1 = center.add(Math.cos(a1) * inner, lift, Math.sin(a1) * inner);
            Vec3 i0 = center.add(Math.cos(a0) * inner, lift, Math.sin(a0) * inner);
            quad2(out, pose, o0, o1, i1, i0, r[0], r[1], r[2], r[3], rgb, alpha);
        }
    }

    // 立体弧形火浪墙：向外倾斜推进，分三层高度段并在顶端自然羽化消散
    public static void wall(MultiBufferSource buffers, PoseStack pose, Vec3 center, double radius, double height, float scroll, int rgb, float alpha) {
        if (alpha <= 0.01F || height <= 0.02) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(FLAME);
        int segments = Math.max(24, (int) (radius * 9));
        int layers = 3;
        int hotRgb = mix(rgb, 0xFFF4C8, 0.45F);
        int tipRgb = mix(rgb, 0x5A1206, 0.55F);
        for (int ly = 0; ly < layers; ly++) {
            float s0 = ly / (float) layers;
            float s1 = (ly + 1) / (float) layers;
            double y0 = height * s0;
            double y1 = height * s1;
            float a0 = alpha * (s0 == 0 ? 0.55F : (1.0F - s0 * 0.55F));
            float a1 = alpha * (ly == layers - 1 ? 0.0F : (1.0F - s1 * 0.55F));
            int c0 = ly == 0 ? hotRgb : rgb;
            int c1 = ly == layers - 1 ? tipRgb : rgb;
            float v0 = scroll + (1.0F - s1);
            float v1 = scroll + (1.0F - s0);
            for (int i = 0; i < segments; i++) {
                double ang0 = i * Math.PI * 2 / segments;
                double ang1 = (i + 1) * Math.PI * 2 / segments;
                double wave0 = Math.sin(ang0 * 6 - scroll * 8.0) * 0.12;
                double wave1 = Math.sin(ang1 * 6 - scroll * 8.0) * 0.12;
                double r00 = radius + s0 * 0.35 + wave0 * s0;
                double r01 = radius + s0 * 0.35 + wave1 * s0;
                double r10 = radius + s1 * 0.35 + wave0 * s1;
                double r11 = radius + s1 * 0.35 + wave1 * s1;
                Vec3 b0 = center.add(Math.cos(ang0) * r00, y0, Math.sin(ang0) * r00);
                Vec3 b1 = center.add(Math.cos(ang1) * r01, y0, Math.sin(ang1) * r01);
                Vec3 t0 = center.add(Math.cos(ang0) * r10, y1, Math.sin(ang0) * r10);
                Vec3 t1 = center.add(Math.cos(ang1) * r11, y1, Math.sin(ang1) * r11);
                float u0 = i * 0.35F;
                quadGrad2(out, pose, t0, t1, b1, b0, u0, v0, u0 + 0.35F, v1, c1, a1, c0, a0);
            }
        }
    }

    // 3D 螺旋扭曲空心火柱 + 外围盘旋火带（彻底替代原有的米字交叉平板）
    public static void column(MultiBufferSource buffers, PoseStack pose, Vec3 base, Vec3 axis, double width0, double width1,
                              int planes, float scroll, float repeat, int rgb, float alpha) {
        if (alpha <= 0.01F || axis.lengthSqr() < 1.0E-6) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(FLAME);
        Vec3 dir = axis.normalize();
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        if (side.lengthSqr() < 1.0E-4) {
            side = dir.cross(new Vec3(1, 0, 0));
        }
        side = side.normalize();
        Vec3 other = dir.cross(side).normalize();

        int sides = Math.max(10, planes * 4);
        int rings = 5;
        int baseColor = mix(rgb, 0xFFF4D0, 0.35F);
        int tipColor = mix(rgb, 0x6A1408, 0.5F);

        for (int r = 0; r < rings; r++) {
            float s0 = r / (float) rings;
            float s1 = (r + 1) / (float) rings;
            Vec3 c0 = base.add(axis.scale(s0));
            Vec3 c1 = base.add(axis.scale(s1));

            double rad0 = Mth.lerp(s0, width0, width1) * (1.0 + 0.14 * Math.sin(s0 * Math.PI * 2 + scroll * 5.0));
            double rad1 = Mth.lerp(s1, width0, width1) * (1.0 + 0.14 * Math.sin(s1 * Math.PI * 2 + scroll * 5.0));
            if (r == 0) {
                rad0 *= 1.18;
            }
            float a0 = alpha * (r == 0 ? 0.35F : (float) Math.sin(s0 * Math.PI * 0.88));
            float a1 = alpha * (r == rings - 1 ? 0.0F : (float) Math.sin(s1 * Math.PI * 0.88));
            int col0 = mix(baseColor, tipColor, s0 * s0);
            int col1 = mix(baseColor, tipColor, s1 * s1);
            double twist0 = s0 * 1.8 + scroll * 2.2;
            double twist1 = s1 * 1.8 + scroll * 2.2;
            float v0 = scroll + (1.0F - s1) * repeat;
            float v1 = scroll + (1.0F - s0) * repeat;

            for (int i = 0; i < sides; i++) {
                double ang0 = i * Math.PI * 2 / sides;
                double ang1 = (i + 1) * Math.PI * 2 / sides;
                Vec3 p00 = c0.add(side.scale(Math.cos(ang0 + twist0) * rad0)).add(other.scale(Math.sin(ang0 + twist0) * rad0));
                Vec3 p01 = c0.add(side.scale(Math.cos(ang1 + twist0) * rad0)).add(other.scale(Math.sin(ang1 + twist0) * rad0));
                Vec3 p10 = c1.add(side.scale(Math.cos(ang0 + twist1) * rad1)).add(other.scale(Math.sin(ang0 + twist1) * rad1));
                Vec3 p11 = c1.add(side.scale(Math.cos(ang1 + twist1) * rad1)).add(other.scale(Math.sin(ang1 + twist1) * rad1));
                float u0 = i / (float) sides * 2.0F;
                float u1 = (i + 1) / (float) sides * 2.0F;
                quadGrad2(out, pose, p10, p11, p01, p00, u0, v0, u1, v1, col1, a1, col0, a0);
            }
        }

        // 外围缠绕双螺旋上升流光带，增强立体爆裂感
        VertexConsumer glow = buffers.getBuffer(GLOW);
        float[] tr = TRAIL;
        int steps = 12;
        for (int strand = 0; strand < 2; strand++) {
            double phaseOffset = strand * Math.PI;
            for (int s = 0; s < steps; s++) {
                float t0 = s / (float) steps;
                float t1 = (s + 1) / (float) steps;
                double a0 = phaseOffset + t0 * Math.PI * 3.2 - scroll * 6.5;
                double a1 = phaseOffset + t1 * Math.PI * 3.2 - scroll * 6.5;
                double r0 = Mth.lerp(t0, width0, width1) * 1.22;
                double r1 = Mth.lerp(t1, width0, width1) * 1.22;
                double ribbonHalf = Mth.lerp(t0, width0, width1) * 0.28 * (1.0 - t0 * 0.5);
                Vec3 p0 = base.add(axis.scale(t0)).add(side.scale(Math.cos(a0) * r0)).add(other.scale(Math.sin(a0) * r0));
                Vec3 p1 = base.add(axis.scale(t1)).add(side.scale(Math.cos(a1) * r1)).add(other.scale(Math.sin(a1) * r1));
                Vec3 upOff = dir.scale(ribbonHalf);
                float fa0 = alpha * (float) Math.sin(t0 * Math.PI);
                float fa1 = alpha * (float) Math.sin(t1 * Math.PI);
                float u0 = tr[0] + (tr[2] - tr[0]) * t0;
                float u1 = tr[0] + (tr[2] - tr[0]) * t1;
                quadGrad2(glow, pose, p0.add(upOff), p1.add(upOff), p1.subtract(upOff), p0.subtract(upOff),
                        u0, tr[1], u1, tr[3], baseColor, fa1, baseColor, fa0);
            }
        }
    }

    // 3D 体积喷焰锥：三层反向旋转的螺旋涡流锥筒 + 盘旋火流带（彻底替代放大粒子纸片）
    public static void coneJet(MultiBufferSource buffers, PoseStack pose, Vec3 base, Vec3 dir, Vec3 side, Vec3 up,
                               double length, float time, float alpha) {
        if (alpha <= 0.01F || length <= 0.1) {
            return;
        }
        // 1. 内层高压白金火芯锥
        coneLayer(buffers, pose, base, dir, side, up, length * 0.82, 0.16, 0.72, 12, 6,
                -time * 0.38F, time * 0.28F, 0xFFF8D8, 0xFF9A28, alpha * 0.95F);
        // 2. 中层翻滚烈焰主锥
        coneLayer(buffers, pose, base, dir, side, up, length, 0.24, 1.45, 14, 7,
                -time * 0.28F, -time * 0.22F, 0xFFD878, 0xD83810, alpha * 0.85F);
        // 3. 外层暗红余烬气浪壳
        coneLayer(buffers, pose, base, dir, side, up, length * 1.05, 0.32, 1.95, 14, 6,
                -time * 0.20F, time * 0.16F, 0xFF7A22, 0x5A1006, alpha * 0.55F);

        // 4. 三股沿喷射锥高速盘旋冲出的螺旋火舌流带
        VertexConsumer glow = buffers.getBuffer(GLOW);
        float[] tr = TRAIL;
        int steps = 16;
        for (int strand = 0; strand < 3; strand++) {
            double basePhase = strand * (Math.PI * 2 / 3.0);
            for (int s = 0; s < steps; s++) {
                float t0 = s / (float) steps;
                float t1 = (s + 1) / (float) steps;
                double a0 = basePhase + t0 * Math.PI * 3.6 - time * 0.45;
                double a1 = basePhase + t1 * Math.PI * 3.6 - time * 0.45;
                double r0 = Mth.lerp(t0, 0.20, 1.55);
                double r1 = Mth.lerp(t1, 0.20, 1.55);
                Vec3 c0 = base.add(dir.scale(t0 * length)).add(up.scale(t0 * t0 * 0.35));
                Vec3 c1 = base.add(dir.scale(t1 * length)).add(up.scale(t1 * t1 * 0.35));
                Vec3 radVec0 = side.scale(Math.cos(a0)).add(up.scale(Math.sin(a0)));
                Vec3 radVec1 = side.scale(Math.cos(a1)).add(up.scale(Math.sin(a1)));
                Vec3 p0 = c0.add(radVec0.scale(r0));
                Vec3 p1 = c1.add(radVec1.scale(r1));
                double w0 = (0.12 + t0 * 0.25) * (1.0 - t0 * 0.4);
                double w1 = (0.12 + t1 * 0.25) * (1.0 - t1 * 0.4);
                int col0 = mix(0xFFF2B8, 0xB82808, t0);
                int col1 = mix(0xFFF2B8, 0xB82808, t1);
                float fa0 = alpha * (float) Math.sin(t0 * Math.PI);
                float fa1 = alpha * (float) Math.sin(t1 * Math.PI);
                float u0 = tr[0] + (tr[2] - tr[0]) * t0;
                float u1 = tr[0] + (tr[2] - tr[0]) * t1;
                quadGrad2(glow, pose, p0.add(radVec0.scale(w0)), p1.add(radVec1.scale(w1)),
                        p1.subtract(radVec1.scale(w1)), p0.subtract(radVec0.scale(w0)),
                        u0, tr[1], u1, tr[3], col0, fa0, col1, fa1);
            }
        }
    }

    private static void coneLayer(MultiBufferSource buffers, PoseStack pose, Vec3 base, Vec3 dir, Vec3 side, Vec3 up,
                                  double length, double rStart, double rEnd, int sides, int rings,
                                  float scroll, float spin, int colorStart, int colorEnd, float alpha) {
        VertexConsumer out = buffers.getBuffer(FLAME);
        for (int r = 0; r < rings; r++) {
            float t0 = r / (float) rings;
            float t1 = (r + 1) / (float) rings;
            // 微上扬热浮力曲线
            Vec3 c0 = base.add(dir.scale(t0 * length)).add(up.scale(t0 * t0 * 0.35));
            Vec3 c1 = base.add(dir.scale(t1 * length)).add(up.scale(t1 * t1 * 0.35));
            double pulse0 = 1.0 + 0.10 * Math.sin(t0 * 8.0 + scroll * 10.0);
            double pulse1 = 1.0 + 0.10 * Math.sin(t1 * 8.0 + scroll * 10.0);
            double rad0 = Mth.lerp(Math.pow(t0, 0.85), rStart, rEnd) * pulse0;
            double rad1 = Mth.lerp(Math.pow(t1, 0.85), rStart, rEnd) * pulse1;

            float a0 = alpha * (r == 0 ? 0.3F : (float) (Math.sin(t0 * Math.PI) * (1.0 - t0 * 0.25)));
            float a1 = alpha * (r == rings - 1 ? 0.0F : (float) (Math.sin(t1 * Math.PI) * (1.0 - t1 * 0.25)));
            int col0 = mix(colorStart, colorEnd, t0);
            int col1 = mix(colorStart, colorEnd, t1);
            double twist0 = spin + t0 * 2.4;
            double twist1 = spin + t1 * 2.4;
            float v0 = scroll + t0 * 2.2F;
            float v1 = scroll + t1 * 2.2F;

            for (int i = 0; i < sides; i++) {
                double ang0 = i * Math.PI * 2 / sides;
                double ang1 = (i + 1) * Math.PI * 2 / sides;
                Vec3 p00 = c0.add(side.scale(Math.cos(ang0 + twist0) * rad0)).add(up.scale(Math.sin(ang0 + twist0) * rad0 * 0.88));
                Vec3 p01 = c0.add(side.scale(Math.cos(ang1 + twist0) * rad0)).add(up.scale(Math.sin(ang1 + twist0) * rad0 * 0.88));
                Vec3 p10 = c1.add(side.scale(Math.cos(ang0 + twist1) * rad1)).add(up.scale(Math.sin(ang0 + twist1) * rad1 * 0.88));
                Vec3 p11 = c1.add(side.scale(Math.cos(ang1 + twist1) * rad1)).add(up.scale(Math.sin(ang1 + twist1) * rad1 * 0.88));
                float u0 = i / (float) sides * 2.0F;
                float u1 = (i + 1) / (float) sides * 2.0F;
                quadGrad2(out, pose, p00, p01, p11, p10, u0, v0, u1, v1, col0, a0, col1, a1);
            }
        }
    }

    // 3D 膨胀热浪穹顶/冲击球壳（彻底替代放大到数格宽的 2D 闪光粒子）
    public static void dome(MultiBufferSource buffers, PoseStack pose, Vec3 center, double radius, float scroll, int rgbCore, int rgbEdge, float alpha) {
        if (alpha <= 0.01F || radius <= 0.05) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(FLAME);
        int lonSegments = 16;
        int latBands = 5;
        for (int lat = 0; lat < latBands; lat++) {
            float f0 = lat / (float) latBands;
            float f1 = (lat + 1) / (float) latBands;
            double phi0 = f0 * Math.PI * 0.5;
            double phi1 = f1 * Math.PI * 0.5;
            double y0 = Math.sin(phi0) * radius * 0.85;
            double y1 = Math.sin(phi1) * radius * 0.85;
            double r0 = Math.cos(phi0) * radius;
            double r1 = Math.cos(phi1) * radius;

            float a0 = alpha * (1.0F - f0 * 0.75F);
            float a1 = alpha * (lat == latBands - 1 ? 0.0F : (1.0F - f1 * 0.75F));
            int c0 = mix(rgbCore, rgbEdge, f0);
            int c1 = mix(rgbCore, rgbEdge, f1);
            float v0 = scroll + f0 * 1.2F;
            float v1 = scroll + f1 * 1.2F;

            for (int lon = 0; lon < lonSegments; lon++) {
                double th0 = lon * Math.PI * 2 / lonSegments;
                double th1 = (lon + 1) * Math.PI * 2 / lonSegments;
                Vec3 p00 = center.add(Math.cos(th0) * r0, y0, Math.sin(th0) * r0);
                Vec3 p01 = center.add(Math.cos(th1) * r0, y0, Math.sin(th1) * r0);
                Vec3 p10 = center.add(Math.cos(th0) * r1, y1, Math.sin(th0) * r1);
                Vec3 p11 = center.add(Math.cos(th1) * r1, y1, Math.sin(th1) * r1);
                float u0 = lon / (float) lonSegments * 2.0F;
                float u1 = (lon + 1) / (float) lonSegments * 2.0F;
                quadGrad2(out, pose, p10, p11, p01, p00, u0, v1, u1, v0, c1, a1, c0, a0);
            }
        }
    }

    // 朝向镜头的面片：可绕视线旋转，用于局部发光点缀
    public static void billboard(VertexConsumer out, PoseStack pose, Vec3 center, double size, double angle, float[] r, int rgb, float alpha) {
        if (alpha <= 0.01F) {
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f leftF = camera.getLeftVector();
        Vector3f upF = camera.getUpVector();
        Vec3 left = new Vec3(leftF.x(), leftF.y(), leftF.z());
        Vec3 up = new Vec3(upF.x(), upF.y(), upF.z());
        double cos = Math.cos(angle) * size;
        double sin = Math.sin(angle) * size;
        Vec3 ax = left.scale(cos).add(up.scale(sin));
        Vec3 ay = up.scale(cos).subtract(left.scale(sin));
        quad(out, pose, center.add(ax).add(ay), center.subtract(ax).add(ay), center.subtract(ax).subtract(ay), center.add(ax).subtract(ay),
                r[0], r[1], r[2], r[3], rgb, alpha);
    }

    // 两色线性插值
    public static int mix(int a, int b, float t) {
        float k = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * k));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * k));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * k));
        return (r << 16) | (g << 8) | bl;
    }

    // 立方体：用于熔岩弹与碎岩
    public static void box(MultiBufferSource buffers, RenderType type, PoseStack pose, double r, float[] reg, int rgb, float alpha) {
        VertexConsumer out = buffers.getBuffer(type);
        Vec3 a = new Vec3(-r, -r, -r);
        Vec3 b = new Vec3(r, -r, -r);
        Vec3 c = new Vec3(r, r, -r);
        Vec3 d = new Vec3(-r, r, -r);
        Vec3 e = new Vec3(-r, -r, r);
        Vec3 f = new Vec3(r, -r, r);
        Vec3 g = new Vec3(r, r, r);
        Vec3 h = new Vec3(-r, r, r);
        float u0 = reg[0];
        float v0 = reg[1];
        float u1 = reg[2];
        float v1 = reg[3];
        quad(out, pose, d, c, b, a, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, g, h, e, f, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, h, d, a, e, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, c, g, f, b, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, h, g, c, d, u0, v0, u1, v1, rgb, alpha);
        quad(out, pose, a, b, f, e, u0, v0, u1, v1, rgb, alpha);
    }
}
