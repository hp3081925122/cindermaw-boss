package org.hp.cindermaw.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.hp.cindermaw.Cindermaw;
import org.hp.cindermaw.entity.CindermawAttack;
import org.hp.cindermaw.entity.CindermawEntity;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

// GeckoLib 资源路径与 Blockbench 导出的几何、贴图、动画一一对应。
public final class CindermawModel extends GeoModel<CindermawEntity> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "geo/cindermaw.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "textures/entity/cindermaw.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(Cindermaw.MOD_ID, "animations/cindermaw.animation.json");

    @Override
    public ResourceLocation getModelResource(CindermawEntity entity) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(CindermawEntity entity) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(CindermawEntity entity) {
        return ANIMATION;
    }

    // 空闲与行走时头部叠加视线偏转，技能期间完全交给动画
    @Override
    public void setCustomAnimations(CindermawEntity entity, long instanceId, AnimationState<CindermawEntity> state) {
        super.setCustomAnimations(entity, instanceId, state);
        if (entity.getAttack() != CindermawAttack.NONE || entity.isDeadOrDying()) {
            return;
        }
        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        if (data == null) {
            return;
        }
        getBone("head").ifPresent(head -> {
            head.setRotY(head.getRotY() + Mth.clamp(data.netHeadYaw(), -45.0F, 45.0F) * Mth.DEG_TO_RAD);
            head.setRotX(head.getRotX() + Mth.clamp(data.headPitch(), -25.0F, 25.0F) * Mth.DEG_TO_RAD);
            // 转头时顺带歪头，显得更有生气
            head.setRotZ(head.getRotZ() - Mth.clamp(data.netHeadYaw(), -45.0F, 45.0F) * 0.25F * Mth.DEG_TO_RAD);
        });
    }
}
