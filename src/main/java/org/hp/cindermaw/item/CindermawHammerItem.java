package org.hp.cindermaw.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.hp.cindermaw.entity.MagmaEffect;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// 熔渊锤：重击点燃目标；按住右键蓄力，松开砸地掀起三道熔岩裂缝。
public final class CindermawHammerItem extends SwordItem {
    // 蓄力满所需刻数与砸地冷却
    private static final int CHARGE = 16;
    private static final int COOLDOWN = 80;

    public CindermawHammerItem(Properties properties) {
        super(Tiers.NETHERITE, 7, -3.2F, properties);
    }

    // 普通命中附带 5 秒灼烧
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        target.setSecondsOnFire(5);
        return super.hurtEnemy(stack, target, attacker);
    }

    // 右键开始蓄力
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    // 松开右键：蓄力足够才砸地
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (!(user instanceof Player player) || getUseDuration(stack) - remaining < CHARGE) {
            return;
        }
        player.swing(player.getUsedItemHand(), true);
        if (level.isClientSide) {
            return;
        }
        // 前方一格多向下找地面
        float yaw = player.getYRot();
        Vec3 front = player.position().add(-Math.sin(Math.toRadians(yaw)) * 1.2, 0, Math.cos(Math.toRadians(yaw)) * 1.2);
        var hit = level.clip(new ClipContext(front.add(0, 1.5, 0), front.add(0, -3.0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) {
            return;
        }
        // 冲击环 + 三道裂缝，每段 9 点伤害
        Vec3 at = hit.getLocation();
        MagmaEffect.shockwave(player, at, 2.6F, 0);
        MagmaEffect.fissure(player, at, yaw, 3, 7.0F, 9.0F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2F, 0.7F);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8F, 0.5F);
        player.getCooldowns().addCooldown(this, COOLDOWN);
        stack.hurtAndBreak(3, player, p -> p.broadcastBreakEvent(p.getUsedItemHand()));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.cindermaw.cindermaw_hammer.desc").withStyle(ChatFormatting.GOLD));
    }
}
