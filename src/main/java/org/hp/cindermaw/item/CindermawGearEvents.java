package org.hp.cindermaw.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.hp.cindermaw.Cindermaw;
import org.hp.cindermaw.ModEntities;
import org.hp.cindermaw.ModParticles;

// 炉心胸甲效果：免疫火焰与岩浆；被近身攻击时概率喷火反击。
@Mod.EventBusSubscriber(modid = Cindermaw.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CindermawGearEvents {
    private CindermawGearEvents() {
    }

    // 火焰类伤害直接取消，并熄灭身上的火
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        LivingEntity wearer = event.getEntity();
        if (!event.getSource().is(DamageTypeTags.IS_FIRE) || !wearer.getItemBySlot(EquipmentSlot.CHEST).is(ModEntities.CINDERMAW_CHESTPLATE.get())) {
            return;
        }
        event.setCanceled(true);
        wearer.clearFire();
    }

    // 被近战命中时 35% 概率反喷火焰：点燃并灼伤攻击者
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity wearer = event.getEntity();
        if (wearer.level().isClientSide || !wearer.getItemBySlot(EquipmentSlot.CHEST).is(ModEntities.CINDERMAW_CHESTPLATE.get())) {
            return;
        }
        if (!(event.getSource().getDirectEntity() instanceof LivingEntity attacker) || attacker == wearer || attacker.distanceTo(wearer) > 4.0F) {
            return;
        }
        if (wearer.getRandom().nextFloat() >= 0.35F) {
            return;
        }
        attacker.setSecondsOnFire(4);
        attacker.hurt(wearer.damageSources().thorns(wearer), 3.0F);
        if (wearer.level() instanceof ServerLevel server) {
            // 火焰从胸口喷向攻击者
            Vec3 from = wearer.position().add(0, wearer.getBbHeight() * 0.65, 0);
            Vec3 dir = attacker.position().add(0, attacker.getBbHeight() * 0.5, 0).subtract(from).normalize();
            for (int i = 0; i < 10; i++) {
                server.sendParticles(ModParticles.BLAZE_FLAME.get(), from.x, from.y, from.z, 0, dir.x, dir.y, dir.z, 0.15 + i * 0.03);
            }
            server.sendParticles(ModParticles.EMBER.get(), from.x, from.y, from.z, 8, 0.3, 0.3, 0.3, 0.08);
            server.playSound(null, wearer.getX(), wearer.getY(), wearer.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8F, 0.8F);
        }
    }
}
