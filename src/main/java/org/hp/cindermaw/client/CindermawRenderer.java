package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.hp.cindermaw.Cindermaw;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.entity.CindermawAttack;
import org.hp.cindermaw.entity.CindermawEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

// Boss 渲染：模型、发光层、骨骼坐标回写，以及横扫弧光、喷焰火锥与火山喷柱。
public final class CindermawRenderer extends GeoEntityRenderer<CindermawEntity> {
    // 火山口火焰序列帧：16 帧竖排，实心裁切、不剔除背面
    private static final RenderType VENT_FIRE = RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/effect/vent_fire.png"));
    private static final int FIRE_FRAMES = 16;
    private boolean fireCapture;
    private int fireDrawn;
    private final Matrix4f[] firePose = {new Matrix4f(), new Matrix4f(), new Matrix4f()};
    private final Matrix3f[] fireNormal = {new Matrix3f(), new Matrix3f(), new Matrix3f()};
    private final Vec3[] firePivot = new Vec3[3];
    private static final String[] BONES = {"hand_right", "hand_left", "furnace", "vent_0_flame", "vent_1_flame", "vent_2_flame", "head", "body"};

    public CindermawRenderer(EntityRendererProvider.Context context) {
        super(context, new CindermawModel());
        shadowRadius = 1.8F;
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        addRenderLayer(new Phase2GlowLayer(this));
    }

    // 技能特效可能超出碰撞箱，放宽视锥裁剪
    @Override
    public boolean shouldRender(CindermawEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.shouldRender(x, y, z) && frustum.isVisible(entity.getBoundingBox().inflate(9.0));
    }

    @Override
    public void render(CindermawEntity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        // 开启骨骼矩阵追踪
        for (String name : BONES) {
            getGeoModel().getBone(name).ifPresent(bone -> bone.setTrackingMatrices(true));
        }
        // 模型渲染途中只记录火焰骨骼的矩阵，不能切换缓冲，否则后续方块会用错材质
        fireCapture = true;
        fireDrawn = 0;
        super.render(entity, yaw, partial, pose, buffers, light);
        fireCapture = false;
        ventFire(entity, entity.tickCount + partial, buffers);
        Vec3 origin = new Vec3(Mth.lerp(partial, entity.xo, entity.getX()), Mth.lerp(partial, entity.yo, entity.getY()), Mth.lerp(partial, entity.zo, entity.getZ()));
        // 回写骨骼世界坐标：用插值后的实体位置加骨骼相对位置，避免移动时抖动
        for (int i = 0; i < BONES.length; i++) {
            GeoBone bone = getGeoModel().getBone(BONES[i]).orElse(null);
            if (bone != null) {
                var p = bone.getLocalPosition();
                entity.clientBones[i] = origin.add(p.x, p.y, p.z);
            }
        }
        if (entity.isDeadOrDying()) {
            if (entity.deathTime >= 56 && entity.deathTime < 66) {
                float dt = (entity.deathTime - 56 + partial) / 10.0F;
                VfxDraw.dome(buffers, pose, new Vec3(0, 0.8, 0), 1.2 + dt * 5.2, -dt * 1.5F, 0xFFF2B0, 0xA82008, (1.0F - dt) * 0.9F);
            }
            return;
        }
        CindermawAttack attack = entity.getAttack();
        float age = entity.attackAge(partial);
        float time = entity.tickCount + partial;
        sweepTrail(entity, attack, age, origin, time, pose, buffers);
        if (attack == CindermawAttack.BREATH) {
            breathCone(entity, age, origin, partial, time, pose, buffers);
        }
        ventJets(entity, attack, age, origin, time, pose, buffers);
        burstShells(entity, attack, age, origin, time, pose, buffers);
    }

    // 立体十字刀光弧带 + 拳击落点 3D 冲击波穹顶
    private void sweepTrail(CindermawEntity entity, CindermawAttack attack, float age, Vec3 origin, float time, PoseStack pose, MultiBufferSource buffers) {
        int hand = -1;
        if (attack == CindermawAttack.SWEEP && age >= 9 && age < 15) {
            hand = CindermawEntity.BONE_HAND_RIGHT;
        } else if (attack == CindermawAttack.SWEEP && age >= 22 && age < 28) {
            hand = CindermawEntity.BONE_HAND_LEFT;
        }
        if (hand >= 0 && entity.clientBones[hand] != null) {
            entity.clientTrail.add(new Vec3[]{entity.clientBones[hand], new Vec3(age, 0, 0)});
        }
        // 只保留最近五刻的采样
        entity.clientTrail.removeIf(sample -> attack != CindermawAttack.SWEEP || age - sample[1].x > 5 || age < sample[1].x);
        int count = entity.clientTrail.size();
        if (count >= 2) {
            VertexConsumer out = buffers.getBuffer(VfxDraw.GLOW);
            float[] r = VfxDraw.TRAIL;
            for (int i = 0; i < count - 1; i++) {
                Vec3[] a = entity.clientTrail.get(i);
                Vec3[] b = entity.clientTrail.get(i + 1);
                float fadeA = 1.0F - (float) (age - a[1].x) / 5.0F;
                float fadeB = 1.0F - (float) (age - b[1].x) / 5.0F;
                double hA = 1.05 * fadeA;
                double hB = 1.05 * fadeB;
                Vec3 pa = a[0].subtract(origin);
                Vec3 pb = b[0].subtract(origin);
                Vec3 radA = new Vec3(pa.x, 0, pa.z);
                Vec3 radB = new Vec3(pb.x, 0, pb.z);
                if (radA.lengthSqr() > 1.0E-4) {
                    radA = radA.normalize().scale(hA * 0.9);
                }
                if (radB.lengthSqr() > 1.0E-4) {
                    radB = radB.normalize().scale(hB * 0.9);
                }
                float u0 = r[0] + (r[2] - r[0]) * (1 - fadeA);
                float u1 = r[0] + (r[2] - r[0]) * (1 - fadeB);
                // 竖直弧面 + 水平径向刀浪面交叉，任何视角都有饱满的立体挥击感
                VfxDraw.quadGrad2(out, pose, pa.add(0, hA, 0), pb.add(0, hB, 0), pb.add(0, -hB, 0), pa.add(0, -hA, 0),
                        u0, r[1], u1, r[3], 0xFF6A1E, fadeB, 0xFF6A1E, fadeA);
                VfxDraw.quadGrad2(out, pose, pa.add(0, hA * 0.42, 0), pb.add(0, hB * 0.42, 0), pb.add(0, -hB * 0.42, 0), pa.add(0, -hA * 0.42, 0),
                        u0, r[1], u1, r[3], 0xFFF0A8, fadeB, 0xFFF0A8, fadeA);
                VfxDraw.quadGrad2(out, pose, pa.add(radA), pb.add(radB), pb.subtract(radB.scale(0.45)), pa.subtract(radA.scale(0.45)),
                        u0, r[1], u1, r[3], 0xFF8828, fadeB * 0.85F, 0xFF8828, fadeA * 0.85F);
            }
        }
        // 挥拳命中瞬间：3D 膨胀热浪半球替代放大的 2D 闪光粒子
        if (attack == CindermawAttack.SWEEP) {
            for (int hitTick : new int[]{11, 24}) {
                float since = age - hitTick;
                if (since >= 0 && since < 6) {
                    int hIdx = hitTick == 11 ? CindermawEntity.BONE_HAND_RIGHT : CindermawEntity.BONE_HAND_LEFT;
                    Vec3 hitPos = entity.clientBones[hIdx];
                    if (hitPos != null) {
                        float t = since / 6.0F;
                        Vec3 rel = new Vec3(hitPos.x - origin.x, 0.05, hitPos.z - origin.z);
                        VfxDraw.dome(buffers, pose, rel, 0.5 + t * 1.9, -time * 0.35F, 0xFFF0A0, 0xD83810, (1.0F - t) * 0.85F);
                        VfxDraw.ring(buffers, VfxDraw.GLOW, pose, rel, 0.6 + t * 2.1, Math.max(0.1, 0.2 + t * 1.5), 0.0, VfxDraw.RING, 0xFF8A28, (1.0F - t) * 0.8F);
                    }
                }
            }
        }
    }

    // 喷焰：3D 多层螺旋涡流火锥 + 炉口聚能环（彻底移除放大的 2D 火团纸片）
    private void breathCone(CindermawEntity entity, float age, Vec3 origin, float partial, float time, PoseStack pose, MultiBufferSource buffers) {
        if (age < 13 || age > 53) {
            return;
        }
        float ramp = Math.min(1.0F, (age - 13) / 3.0F) * Math.min(1.0F, (53 - age) / 4.0F);
        Vec3 furnace = entity.clientBones[CindermawEntity.BONE_FURNACE];
        if (furnace == null) {
            furnace = entity.local(0, 1.36, 0.71);
        }
        float yawDeg = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(-7);
        Vec3 dir = new Vec3(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 side = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3 up = dir.cross(side).normalize();
        Vec3 base = furnace.subtract(origin).add(dir.scale(0.28));
        float reach = 8.5F * Math.min(1.0F, (age - 13) / 5.0F);

        VfxDraw.coneJet(buffers, pose, base, dir, side, up, reach, time, ramp);

        // 炉口小尺寸高亮能量光晕
        VertexConsumer out = buffers.getBuffer(VfxDraw.GLOW);
        float flare = 0.48F + Mth.sin(time * 2.1F) * 0.06F;
        VfxDraw.billboard(out, pose, base, flare, time * 0.12, VfxDraw.DISC, 0xFFF2B8, ramp * 0.9F);
    }

    // 咆哮与新星聚能爆发时的 3D 膨胀冲击球壳
    private void burstShells(CindermawEntity entity, CindermawAttack attack, float age, Vec3 origin, float time, PoseStack pose, MultiBufferSource buffers) {
        Vec3 furnace = entity.clientBones[CindermawEntity.BONE_FURNACE];
        Vec3 relFurnace = furnace != null ? furnace.subtract(origin) : new Vec3(0, 1.36, 0.0);
        if (attack == CindermawAttack.NOVA && age >= 22 && age < 30) {
            float t = (age - 22) / 8.0F;
            VfxDraw.dome(buffers, pose, new Vec3(0, 0.1, 0), 0.9 + t * 3.8, -time * 0.35F, 0xFFF4B8, 0xC82808, (1.0F - t) * 0.85F);
        } else if (attack == CindermawAttack.ROAR && age >= 26 && age < 35) {
            float t = (age - 26) / 9.0F;
            VfxDraw.dome(buffers, pose, new Vec3(relFurnace.x, 0.2, relFurnace.z), 1.0 + t * 4.5, -time * 0.4F, 0xFFE898, 0xB82208, (1.0F - t) * 0.85F);
            VfxDraw.ring(buffers, VfxDraw.GLOW, pose, new Vec3(0, 0.08, 0), 1.2 + t * 5.0, Math.max(0.2, 0.5 + t * 3.8), 0.0, VfxDraw.RING, 0xFF7A24, (1.0F - t) * 0.85F);
        }
    }

    // 记录火焰骨骼的变换矩阵：此时矩阵已移回模型原点，枢轴单独保存
    @Override
    public void renderCubesOfBone(PoseStack pose, GeoBone bone, VertexConsumer buffer, int light, int overlay, float red, float green, float blue, float alpha) {
        super.renderCubesOfBone(pose, bone, buffer, light, overlay, red, green, blue, alpha);
        if (!fireCapture || !bone.getName().endsWith("_flame")) {
            return;
        }
        int v = bone.getName().charAt(5) - '0';
        if (v < 0 || v > 2 || (fireDrawn & (1 << v)) != 0) {
            return;
        }
        fireDrawn |= 1 << v;
        firePose[v].set(pose.last().pose());
        fireNormal[v].set(pose.last().normal());
        firePivot[v] = new Vec3(bone.getPivotX() / 16.0, bone.getPivotY() / 16.0, bone.getPivotZ() / 16.0);
    }

    // 火山口火焰（仿营火）：模型画完后按记录的骨骼矩阵绘制，跟随火山口倾斜
    private void ventFire(CindermawEntity entity, float time, MultiBufferSource buffers) {
        float heat = 1.0F;
        int speed = 2;
        // 二阶段火焰尺寸不变，只加快跳动
        if (entity.isPhase2()) {
            speed = 1;
        }
        // 死亡过程中火焰逐渐熄灭
        if (entity.isDeadOrDying()) {
            heat = Math.max(0.0F, 1.0F - entity.deathTime / 50.0F);
        }
        if (heat <= 0.05F) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(VENT_FIRE);
        PoseStack bonePose = new PoseStack();
        for (int v = 0; v < 3; v++) {
            if ((fireDrawn & (1 << v)) == 0) {
                continue;
            }
            bonePose.last().pose().set(firePose[v]);
            bonePose.last().normal().set(fireNormal[v]);
            // 中间火山口更大
            double w = 6.5 / 16.0;
            double h = 12.0 / 16.0;
            if (v == 1) {
                w = 9.5 / 16.0;
                h = 16.0 / 16.0;
            }
            h *= heat;
            Vec3 base = firePivot[v].add(0, -1.5 / 16.0, 0);
            // 三个口错开帧，避免同步
            int frame = ((int) (time / speed) + v * 5) % FIRE_FRAMES;
            float v0 = frame / (float) FIRE_FRAMES;
            float v1 = (frame + 1) / (float) FIRE_FRAMES;
            // 营火式竖直火焰面，四片按 45 度均分成米字，减少交叉留下的空隙
            double half = w * 0.62;
            for (int k = 0; k < 4; k++) {
                double angle = k * Math.PI / 4;
                double dx = Math.cos(angle) * half;
                double dz = Math.sin(angle) * half;
                VfxDraw.quad(out, bonePose, base.add(-dx, h, -dz), base.add(dx, h, dz), base.add(dx, 0, dz), base.add(-dx, 0, -dz),
                        0, v0, 1, v1, 0xFFFFFF, 1.0F);
            }
        }
    }

    // 火山喷柱：齐射时三口喷发
    private void ventJets(CindermawEntity entity, CindermawAttack attack, float age, Vec3 origin, float time, PoseStack pose, MultiBufferSource buffers) {
        for (int v = 0; v < 3; v++) {
            Vec3 vent = entity.clientBones[CindermawEntity.BONE_VENT + v];
            if (vent == null) {
                continue;
            }
            double length = 0;
            if (attack == CindermawAttack.BARRAGE) {
                for (int shot = 12; shot <= 36; shot += 12) {
                    float since = age - shot;
                    if (since >= -2 && since < 8) {
                        float pulse = Math.max(0, Math.min(1, (since + 2) / 2.0F)) * Math.max(0, 1 - Math.max(0, since) / 8.0F);
                        length = Math.max(length, 4.5 * pulse);
                    }
                }
            }
            if (attack == CindermawAttack.ROAR && age < 30) {
                length = Math.max(length, 2.0 + Mth.sin(time * 1.5F + v) * 0.5);
            }
            if (length <= 0.05) {
                continue;
            }
            Vec3 base = vent.subtract(origin);
            VfxDraw.column(buffers, pose, base, new Vec3(0, length, 0), 0.30, 0.14, 3, -time * 0.25F, 1.2F, 0xFF7A26, 0.9F);
            VfxDraw.column(buffers, pose, base, new Vec3(0, length * 0.65, 0), 0.16, 0.07, 3, -time * 0.32F, 1.0F, 0xFFF0A8, 0.9F);
        }
    }
}
