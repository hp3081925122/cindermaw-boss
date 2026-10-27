package org.hp.cindermaw.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.hp.cindermaw.ModEntities;
import org.hp.cindermaw.entity.CindermawEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// 熔渊炉核：对方块使用，在该处唤醒熔渊炉心。
public final class CindermawCoreItem extends Item {
    public CindermawCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        // 客户端直接返回成功，交给服务端生成
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        CindermawEntity boss = ModEntities.CINDERMAW.get().create(server);
        if (boss == null) {
            return InteractionResult.FAIL;
        }
        // 朝向召唤者，落在方块中心
        float yaw = 0;
        if (context.getPlayer() != null) {
            yaw = context.getPlayer().getYRot() + 180.0F;
        }
        boss.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0);
        boss.setYHeadRot(yaw);
        boss.setYBodyRot(yaw);
        // 空间不足时不召唤，提示玩家
        if (!server.noCollision(boss)) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.translatable("item.cindermaw.cindermaw_core.no_space").withStyle(ChatFormatting.RED), true);
            }
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.0F, 0.8F);
            return InteractionResult.FAIL;
        }
        boss.finalizeSpawn(server, server.getCurrentDifficultyAt(pos), MobSpawnType.TRIGGERED, null, null);
        boss.beginEntrance();
        server.addFreshEntity(boss);
        // 生存模式消耗一个
        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.cindermaw.cindermaw_core.desc").withStyle(ChatFormatting.GOLD));
    }
}
