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
    private static final String[] BONES ={"hand_right", "hand_left", "furnace", "vent_0_flame", "vent_1_flame", "vent_2_flame", "head", "body"};

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
            return;
        }
        CindermawAttack attack = entity.getAttack();
        float age = entity.attackAge(partial);
        float time = entity.tickCount + partial;
        sweepTrail(entity, attack, age, origin, pose, buffers);
        if (attack == CindermawAttack.BREATH) {
            breathCone(entity, age, origin, partial, time, pose, buffers);
        }
        ventJets(entity, attack, age, origin, time, pose, buffers);
    }

    // 横扫弧光：记录挥击拳头的轨迹，画成渐隐的发光带
    private void sweepTrail(CindermawEntity entity, CindermawAttack attack, float age, Vec3 origin, PoseStack pose, MultiBufferSource buffers) {
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
        if (count < 2) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(VfxDraw.GLOW);
        float[] r = VfxDraw.TRAIL;
        for (int i = 0; i < count - 1; i++) {
            Vec3[] a = entity.clientTrail.get(i);
            Vec3[] b = entity.clientTrail.get(i + 1);
            float fadeA = 1.0F - (float) (age - a[1].x) / 5.0F;
            float fadeB = 1.0F - (float) (age - b[1].x) / 5.0F;
            double hA = 1.1 * fadeA;
            double hB = 1.1 * fadeB;
            Vec3 pa = a[0].subtract(origin);
            Vec3 pb = b[0].subtract(origin);
            float u0 = r[0] + (r[2] - r[0]) * (1 - fadeA);
            float u1 = r[0] + (r[2] - r[0]) * (1 - fadeB);
            // 外层橙红宽带 + 内层亮黄窄带
            VfxDraw.quad2(out, pose, pa.add(0, hA, 0), pb.add(0, hB, 0), pb.add(0, -hB, 0), pa.add(0, -hA, 0), u0, r[1], u1, r[3], 0xFF6A1E, Math.min(fadeA, fadeB));
            VfxDraw.quad2(out, pose, pa.add(0, hA * 0.45, 0), pb.add(0, hB * 0.45, 0), pb.add(0, -hB * 0.45, 0), pa.add(0, -hA * 0.45, 0), u0, r[1], u1, r[3], 0xFFE08A, Math.min(fadeA, fadeB));
        }
    }

    // 喷焰：一串朝向镜头的火团沿喷射方向滚动，越远越大、颜色由白黄退到暗红
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
        Vec3 base = furnace.subtract(origin).add(dir.scale(0.3));
        float reach = 8.5F * Math.min(1.0F, (age - 13) / 5.0F);
        VertexConsumer out = buffers.getBuffer(VfxDraw.GLOW);
        int count = 26;
        for (int i = 0; i < count; i++) {
            // 每个火团的循环进度，用黄金比错开避免成排
            float flow = time * 0.11F + i * 0.618F;
            float phase = flow - Mth.floor(flow);
            int cycle = Mth.floor(flow);
            float dist = phase * reach;
            // 每轮循环随机一个偏离方向，越远散得越开
            float seed = Mth.sin(i * 12.9898F + cycle * 78.233F) * 43758.547F;
            seed = seed - Mth.floor(seed);
            double spreadAngle = seed * Math.PI * 2;
            double spread = dist * 0.22 * (0.4 + seed * 0.6);
            Vec3 at = base.add(dir.scale(dist)).add(side.scale(Math.cos(spreadAngle) * spread)).add(up.scale(Math.sin(spreadAngle) * spread * 0.7 + phase * phase * 0.6));
            double size = 0.3 + phase * 1.5;
            int rgb;
            if (phase < 0.35F) {
                rgb = VfxDraw.mix(0xFFF0C0, 0xFF8A2A, phase / 0.35F);
            } else {
                rgb = VfxDraw.mix(0xFF8A2A, 0x7A1A08, (phase - 0.35F) / 0.65F);
            }
            float fade = Math.min(1.0F, phase * 8.0F) * (1.0F - phase * phase);
            VfxDraw.billboard(out, pose, at, size, seed * 6.28 + time * 0.08 * (i % 2 * 2 - 1), VfxDraw.PUFF[i % 2], rgb, 0.95F * fade * ramp);
        }
        // 炉口亮芯
        float flare = 0.9F + Mth.sin(time * 2.1F) * 0.12F;
        VfxDraw.billboard(out, pose, base, 0.85 * flare, time * 0.05, VfxDraw.DISC, 0xFFD890, ramp);
        VfxDraw.billboard(out, pose, base.add(dir.scale(0.6)), 0.55, -time * 0.07, VfxDraw.PUFF[0], 0xFFF4D0, ramp);
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
            VfxDraw.column(buffers, pose, base, new Vec3(0, length, 0), 0.32, 0.12, 3, -time * 0.25F, 1.0F, 0xFF7A26, 0.9F);
            VfxDraw.column(buffers, pose, base, new Vec3(0, length * 0.6, 0), 0.18, 0.06, 3, -time * 0.3F, 1.0F, 0xFFE6A0, 0.9F);
        }
    }
}
