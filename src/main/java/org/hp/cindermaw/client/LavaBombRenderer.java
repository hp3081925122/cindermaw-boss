package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.entity.LavaBomb;

// 熔岩弹：旋转的玄武岩外壳包着发光熔核，落点显示倒计时预警圈。
public final class LavaBombRenderer extends EntityRenderer<LavaBomb> {
    public LavaBombRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(LavaBomb bomb) {
        return VfxDraw.ATLAS;
    }

    // 弹体或落点任一可见就渲染，避免头顶的弹体离开视野后预警消失
    @Override
    public boolean shouldRender(LavaBomb bomb, Frustum frustum, double x, double y, double z) {
        if (!bomb.ready()) {
            return false;
        }
        return super.shouldRender(bomb, frustum, x, y, z) || bomb.shouldRender(x, y, z) && frustum.isVisible(new AABB(bomb.landing(), bomb.landing()).inflate(3));
    }

    @Override
    public void render(LavaBomb bomb, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!bomb.ready()) {
            return;
        }
        Vec3 here = new Vec3(Mth.lerp(partial, bomb.xo, bomb.getX()), Mth.lerp(partial, bomb.yo, bomb.getY()), Mth.lerp(partial, bomb.zo, bomb.getZ()));
        Vec3 landing = bomb.landing().subtract(here).add(0, 0.04, 0);
        float age = bomb.age(partial);
        if (bomb.impacted()) {
            // 命中后数刻的 3D 热浪穹顶与扩散环
            float t = Math.min(1.0F, bomb.impactAge(partial) / LavaBomb.LINGER);
            VfxDraw.dome(buffers, pose, landing, 0.65 + 2.1 * t, -age * 0.35F, 0xFFF0A8, 0xD83810, (1 - t) * 0.9F);
            VfxDraw.ring(buffers, VfxDraw.GLOW, pose, landing.add(0, 0.02, 0), 0.7 + 2.2 * t, Math.max(0.15, 0.3 + 1.5 * t), 0.0, VfxDraw.RING, 0xFF8A30, 1 - t);
            return;
        }
        // 落点预警：外圈常亮，内圈随剩余时间收缩
        float progress = Math.min(1.0F, age / bomb.flightTicks());
        float blink = 0.6F + 0.4F * Mth.sin(age * (0.5F + progress * 1.2F));
        VfxDraw.flat(buffers, VfxDraw.GLOW, pose, landing, 2.3, VfxDraw.WARN, 0xFF4A1A, 0.5F + 0.5F * progress * blink);
        VfxDraw.flat(buffers, VfxDraw.GLOW, pose, landing.add(0, 0.01, 0), 2.3 * (1 - progress) + 0.2, VfxDraw.WARN, 0xFFC070, 0.85F);
        // 弹体：外壳旋转，熔核脉动
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(age * 17));
        pose.mulPose(Axis.XP.rotationDegrees(age * 11));
        VfxDraw.box(buffers, VfxDraw.SOFT, pose, 0.3, VfxDraw.ROCK, 0xFFFFFF, 1.0F);
        float pulse = 0.36F + Mth.sin(age * 0.9F) * 0.03F;
        VfxDraw.box(buffers, VfxDraw.GLOW, pose, pulse, VfxDraw.CORE, 0xFFA040, 0.85F);
        pose.popPose();
        VfxDraw.billboard(buffers.getBuffer(VfxDraw.GLOW), pose, Vec3.ZERO, 0.56, age * 0.15, VfxDraw.DISC, 0xFF8A30, 0.65F);
    }
}
