package org.hp.cindermaw.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.hp.cindermaw.Cindermaw;
import org.hp.cindermaw.entity.CindermawAttack;
import org.hp.cindermaw.entity.CindermawEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

// 二阶段发光层：全身砖缝裂开透出熔光，随呼吸节奏明暗脉动。
public final class Phase2GlowLayer extends GeoRenderLayer<CindermawEntity> {
    private static final RenderType GLOW = RenderType.eyes(ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/entity/cindermaw_phase2.png"));

    public Phase2GlowLayer(GeoRenderer<CindermawEntity> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack pose, CindermawEntity entity, BakedGeoModel model, RenderType renderType, MultiBufferSource buffers,
                       VertexConsumer buffer, float partial, int light, int overlay) {
        float strength = 0.0F;
        if (entity.isPhase2()) {
            strength = 1.0F;
        }
        // 咆哮蓄力期间裂缝逐渐亮起，第 26 刻正式进入二阶段
        if (entity.getAttack() == CindermawAttack.ROAR) {
            strength = Math.max(strength, Math.min(1.0F, entity.attackAge(partial) / 26.0F));
        }
        // 死亡时裂缝随之熄灭
        if (entity.isDeadOrDying()) {
            strength *= Math.max(0.0F, 1.0F - entity.deathTime / 40.0F);
        }
        if (strength <= 0.01F) {
            return;
        }
        // 呼吸式脉动：叠加混合用颜色深浅控制亮度
        float time = entity.tickCount + partial;
        float pulse = strength * (0.55F + 0.45F * Mth.sin(time * 0.16F));
        getRenderer().reRender(model, pose, buffers, entity, GLOW, buffers.getBuffer(GLOW), partial, 0xF000F0, OverlayTexture.NO_OVERLAY,
                pulse, pulse, pulse, 1.0F);
    }
}
