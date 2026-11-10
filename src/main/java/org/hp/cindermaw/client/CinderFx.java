package org.hp.cindermaw.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.hp.cindermaw.Cindermaw;
import org.hp.cindermaw.ModParticles;
import org.hp.cindermaw.entity.CindermawAttack;
import org.hp.cindermaw.entity.CindermawEntity;
import org.hp.cindermaw.entity.LavaBomb;
import org.hp.cindermaw.entity.MagmaEffect;

// 客户端粒子与镜头震动：由实体客户端刻驱动，与服务端技能时间线同刻。
@Mod.EventBusSubscriber(modid = Cindermaw.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CinderFx {
    private static float shake;
    private static float shakeTime;

    private CinderFx() {
    }

    // ===== 镜头震动 =====

    // 按距离衰减叠加震动强度
    public static void shake(Vec3 at, float strength, float radius) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double distance = player.position().distanceTo(at);
        if (distance > radius) {
            return;
        }
        float amount = strength * (float) (1.0 - distance / radius);
        shake = Math.min(2.5F, Math.max(shake, amount));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && shake > 0) {
            shake *= 0.82F;
            shakeTime += 1;
            if (shake < 0.02F) {
                shake = 0;
            }
        }
    }

    @SubscribeEvent
    public static void onCamera(ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0) {
            return;
        }
        float t = (float) (shakeTime + event.getPartialTick()) * 2.3F;
        event.setPitch(event.getPitch() + Mth.sin(t * 1.7F) * shake * 1.6F);
        event.setYaw(event.getYaw() + Mth.sin(t * 1.3F + 1.1F) * shake * 1.2F);
        event.setRoll(event.getRoll() + Mth.sin(t * 2.1F + 2.3F) * shake * 1.4F);
    }

    // ===== 粒子工具 =====

    private static void spawn(SimpleParticleType type, Vec3 p, double vx, double vy, double vz) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            level.addParticle((ParticleOptions) type, p.x, p.y, p.z, vx, vy, vz);
        }
    }

    private static double spread(RandomSource random, double amount) {
        return (random.nextDouble() - 0.5) * 2 * amount;
    }

    // 球形爆散：count 个粒子以 speed 向四周飞出，lift 为额外上抛
    private static void burst(SimpleParticleType type, Vec3 at, int count, double speed, double lift, RandomSource random) {
        for (int i = 0; i < count; i++) {
            Vec3 dir = new Vec3(spread(random, 1), spread(random, 1), spread(random, 1));
            if (dir.lengthSqr() < 1.0E-4) {
                dir = new Vec3(0, 1, 0);
            }
            dir = dir.normalize().scale(speed * (0.4 + random.nextDouble() * 0.6));
            spawn(type, at, dir.x, dir.y + lift, dir.z);
        }
    }

    // 碎石飞溅：向上抛出并散开
    private static void debris(Vec3 at, int count, double speed, RandomSource random) {
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double s = speed * (0.4 + random.nextDouble() * 0.6);
            spawn(ModParticles.MAGMA_CHUNK.get(), at.add(spread(random, 0.4), 0.1, spread(random, 0.4)),
                    Math.cos(angle) * s, 0.35 + random.nextDouble() * 0.45, Math.sin(angle) * s);
        }
    }

    // 地面火柱伴随星火：主火柱由 3D 网格绘制，粒子重点表现飞溅火星与少量火苗点缀
    private static void fountain(Vec3 at, int flames, double power, RandomSource random) {
        int smallFlames = Math.max(1, flames / 3);
        for (int i = 0; i < smallFlames; i++) {
            spawn(ModParticles.BLAZE_FLAME.get(), at.add(spread(random, 0.25), 0.1, spread(random, 0.25)),
                    spread(random, 0.04), power * (0.45 + random.nextDouble() * 0.35), spread(random, 0.04));
        }
        int embers = flames + 2;
        for (int i = 0; i < embers; i++) {
            spawn(ModParticles.EMBER.get(), at.add(spread(random, 0.32), 0.2, spread(random, 0.32)),
                    spread(random, 0.16), power * (0.75 + random.nextDouble() * 0.85), spread(random, 0.16));
        }
    }

    private static Vec3 bone(CindermawEntity boss, int index, Vec3 fallback) {
        Vec3 p = boss.clientBones[index];
        if (p == null) {
            return fallback;
        }
        return p;
    }

    // ===== 首领 =====

    // 客户端时间可能跳刻，补跑最多三刻，保证单刻触发的特效不丢
    public static void bossTick(CindermawEntity boss) {
        int now = (int) boss.level().getGameTime();
        int from = now;
        if (boss.clientFxAge != Integer.MIN_VALUE) {
            from = Math.max(boss.clientFxAge + 1, now - 2);
        }
        for (int t = from; t <= now; t++) {
            bossTickAt(boss, (int) boss.attackAge(0) - (now - t));
        }
        boss.clientFxAge = now;
    }

    private static void bossTickAt(CindermawEntity boss, int age) {
        RandomSource random = boss.getRandom();
        if (boss.isDeadOrDying()) {
            deathTick(boss, random);
            return;
        }
        // 火山口常驻火星与烟，二阶段翻倍并从炉心冒火
        int ambient = 1;
        if (boss.isPhase2()) {
            ambient = 2;
        }
        for (int v = 0; v < 3; v++) {
            Vec3 vent = bone(boss, CindermawEntity.BONE_VENT + v, boss.ventTop(v));
            for (int i = 0; i < ambient; i++) {
                if (random.nextFloat() < 0.6F) {
                    spawn(ModParticles.EMBER.get(), vent.add(spread(random, 0.3), 0.3, spread(random, 0.3)), spread(random, 0.04), 0.08 + random.nextDouble() * 0.1, spread(random, 0.04));
                }
            }
            if (random.nextFloat() < 0.12F * ambient) {
                spawn(ModParticles.CINDER_SMOKE.get(), vent.add(0, 0.6, 0), spread(random, 0.02), 0.05, spread(random, 0.02));
            }
        }
        if (boss.isPhase2() && random.nextFloat() < 0.5F) {
            Vec3 body = boss.position().add(spread(random, 1.3), 0.6 + random.nextDouble() * 1.8, spread(random, 1.3));
            spawn(ModParticles.EMBER.get(), body, spread(random, 0.03), 0.06, spread(random, 0.03));
        }
        CindermawAttack attack = boss.getAttack();
        if (attack == CindermawAttack.NONE) {
            return;
        }
        Vec3 furnace = bone(boss, CindermawEntity.BONE_FURNACE, boss.local(0, 1.36, 0.71));
        Vec3 handR = bone(boss, CindermawEntity.BONE_HAND_RIGHT, boss.local(-1.2, 0.3, 1.0));
        Vec3 handL = bone(boss, CindermawEntity.BONE_HAND_LEFT, boss.local(1.2, 0.3, 1.0));
        switch (attack) {
            case SLAM -> {
                // 蓄力：双拳聚火
                if (age >= 4 && age < 16) {
                    for (Vec3 hand : new Vec3[]{handR, handL}) {
                        Vec3 from = hand.add(spread(random, 1.2), spread(random, 1.2), spread(random, 1.2));
                        Vec3 pull = hand.subtract(from).scale(0.18);
                        spawn(ModParticles.EMBER.get(), from, pull.x, pull.y, pull.z);
                        spawn(ModParticles.BLAZE_FLAME.get(), hand, spread(random, 0.03), 0.04, spread(random, 0.03));
                    }
                }
            }
            case SWEEP -> {
                // 挥击：火焰沿挥击方向甩出
                Vec3 hand = null;
                double side = 0;
                if (age >= 9 && age < 15) {
                    hand = handR;
                    side = 1;
                } else if (age >= 22 && age < 28) {
                    hand = handL;
                    side = -1;
                }
                if (hand != null) {
                    Vec3 fling = boss.right().scale(side * 0.35).add(boss.forward().scale(0.15));
                    for (int i = 0; i < 4; i++) {
                        spawn(ModParticles.BLAZE_FLAME.get(), hand.add(spread(random, 0.4), spread(random, 0.4), spread(random, 0.4)), fling.x * 0.5, 0.03, fling.z * 0.5);
                        spawn(ModParticles.EMBER.get(), hand, fling.x + spread(random, 0.15), 0.12 + random.nextDouble() * 0.15, fling.z + spread(random, 0.15));
                    }
                }
                if (age == 11 || age == 24) {
                    // 命中瞬间：拳前爆闪、贴地扬尘与碎石
                    Vec3 hit = handR;
                    if (age == 24) {
                        hit = handL;
                    }
                    spawn(ModParticles.HEAT_FLASH.get(), hit, 2.6, 0, 0);
                    debris(hit, 8, 0.3, random);
                    for (int i = 0; i < 6; i++) {
                        spawn(ModParticles.CINDER_SMOKE.get(), new Vec3(hit.x, boss.getY() + 0.2, hit.z).add(spread(random, 0.8), 0, spread(random, 0.8)), spread(random, 0.08), 0.03, spread(random, 0.08));
                    }
                    shake(boss.position(), 0.75F, 12);
                }
            }
            case BREATH -> breathTick(boss, furnace, age, random);
            case BARRAGE -> {
                // 三轮喷发：火山口火柱、闪光与浓烟
                if (age == 12 || age == 24 || age == 36) {
                    for (int v = 0; v < 3; v++) {
                        Vec3 vent = bone(boss, CindermawEntity.BONE_VENT + v, boss.ventTop(v));
                        spawn(ModParticles.HEAT_FLASH.get(), vent.add(0, 0.4, 0), 2.2, 0, 0);
                        fountain(vent.add(0, 0.2, 0), 12, 0.75, random);
                        for (int i = 0; i < 5; i++) {
                            spawn(ModParticles.CINDER_SMOKE.get(), vent.add(0, 0.8, 0), spread(random, 0.08), 0.18 + random.nextDouble() * 0.12, spread(random, 0.08));
                        }
                    }
                    shake(boss.position(), 0.45F, 14);
                } else if (age < 40) {
                    for (int v = 0; v < 3; v++) {
                        Vec3 vent = bone(boss, CindermawEntity.BONE_VENT + v, boss.ventTop(v));
                        spawn(ModParticles.BLAZE_FLAME.get(), vent.add(0, 0.2, 0), spread(random, 0.03), 0.18, spread(random, 0.03));
                    }
                }
            }
            case LEAP -> {
                // 蓄力扬尘，起跳爆发，空中拖火
                Vec3 feet = boss.position();
                if (age < 12 && random.nextFloat() < 0.6F) {
                    spawn(ModParticles.CINDER_SMOKE.get(), feet.add(spread(random, 1.6), 0.2, spread(random, 1.6)), 0, 0.02, 0);
                }
                if (age == 12) {
                    for (int i = 0; i < 18; i++) {
                        double angle = i * Math.PI * 2 / 18;
                        spawn(ModParticles.CINDER_SMOKE.get(), feet.add(Math.cos(angle) * 1.4, 0.2, Math.sin(angle) * 1.4), Math.cos(angle) * 0.25, 0.03, Math.sin(angle) * 0.25);
                    }
                    debris(feet, 10, 0.3, random);
                    shake(feet, 0.6F, 14);
                }
                if (age > 12) {
                    Vec3 body = boss.position().add(0, 1.6, 0);
                    for (int i = 0; i < 4; i++) {
                        spawn(ModParticles.BLAZE_FLAME.get(), body.add(spread(random, 1.2), spread(random, 1.0), spread(random, 1.2)), 0, -0.05, 0);
                    }
                    spawn(ModParticles.CINDER_SMOKE.get(), body, 0, 0, 0);
                }
            }
            case NOVA -> {
                // 聚能：火星从四周吸入炉心，炉心爆闪
                if (age < 22) {
                    for (int i = 0; i < 4; i++) {
                        Vec3 from = furnace.add(spread(random, 3.5), spread(random, 2.0), spread(random, 3.5));
                        Vec3 pull = furnace.subtract(from).scale(0.12);
                        spawn(ModParticles.EMBER.get(), from, pull.x, pull.y, pull.z);
                    }
                }
                if (age == 22) {
                    spawn(ModParticles.HEAT_FLASH.get(), furnace, 4.0, 0, 0);
                    burst(ModParticles.EMBER.get(), furnace, 40, 0.6, 0.1, random);
                }
            }
            case ROAR -> {
                // 咆哮：持续低频震动，火山喷火，热浪爆发
                if (age < 26) {
                    shake(boss.position(), 0.25F, 18);
                    for (int v = 0; v < 3; v++) {
                        Vec3 vent = bone(boss, CindermawEntity.BONE_VENT + v, boss.ventTop(v));
                        fountain(vent, 2, 0.5, random);
                    }
                }
                if (age == 26) {
                    spawn(ModParticles.HEAT_FLASH.get(), furnace, 5.0, 0, 0);
                    burst(ModParticles.EMBER.get(), furnace, 70, 0.9, 0.15, random);
                    burst(ModParticles.BLAZE_FLAME.get(), furnace, 30, 0.5, 0.05, random);
                    shake(boss.position(), 1.2F, 22);
                }
            }
            default -> {
            }
        }
    }

    // 喷焰：蓄力吸火，喷射时主火锥由 3D 螺旋网格渲染，粒子只做高速火星流与末端升腾热烟
    private static void breathTick(CindermawEntity boss, Vec3 furnace, int age, RandomSource random) {
        if (age >= 4 && age < 14) {
            for (int i = 0; i < 3; i++) {
                Vec3 from = furnace.add(spread(random, 1.8), spread(random, 1.5), spread(random, 1.8)).add(boss.forward().scale(1.5));
                Vec3 pull = furnace.subtract(from).scale(0.2);
                spawn(ModParticles.EMBER.get(), from, pull.x, pull.y, pull.z);
            }
        }
        if (age < 14 || age > 50) {
            return;
        }
        double pitch = Math.toRadians(-7);
        Vec3 forward = boss.forward();
        Vec3 dir = new Vec3(forward.x * Math.cos(pitch), Math.sin(pitch), forward.z * Math.cos(pitch));
        Vec3 mouth = furnace.add(dir.scale(0.45));
        for (int i = 0; i < 6; i++) {
            Vec3 v = dir.scale(0.75 + random.nextDouble() * 0.55).add(spread(random, 0.22), spread(random, 0.14), spread(random, 0.22));
            spawn(ModParticles.EMBER.get(), mouth, v.x, v.y, v.z);
        }
        if (random.nextFloat() < 0.45F) {
            Vec3 far = mouth.add(dir.scale(4.5 + random.nextDouble() * 3.5)).add(spread(random, 0.8), 0, spread(random, 0.8));
            spawn(ModParticles.CINDER_SMOKE.get(), far, dir.x * 0.06, 0.06, dir.z * 0.06);
        }
        if (age % 4 == 0) {
            shake(boss.position(), 0.15F, 12);
        }
    }

    // 死亡：全身冒烟与火星，中途崩落碎石，最后炉心爆裂
    private static void deathTick(CindermawEntity boss, RandomSource random) {
        int time = boss.deathTime;
        Vec3 body = boss.position().add(0, 1.4, 0);
        Vec3 furnace = bone(boss, CindermawEntity.BONE_FURNACE, boss.local(0, 1.0, 0.71));
        for (int i = 0; i < 2; i++) {
            spawn(ModParticles.CINDER_SMOKE.get(), body.add(spread(random, 1.4), spread(random, 1.0), spread(random, 1.4)), 0, 0.04, 0);
            spawn(ModParticles.EMBER.get(), body.add(spread(random, 1.4), spread(random, 1.0), spread(random, 1.4)), spread(random, 0.05), 0.1, spread(random, 0.05));
        }
        if (time == 20 || time == 38) {
            debris(boss.position().add(0, 0.5, 0), 16, 0.35, random);
            shake(boss.position(), 0.6F, 16);
        }
        if (time == 58) {
            spawn(ModParticles.HEAT_FLASH.get(), furnace, 6.0, 0, 0);
            burst(ModParticles.EMBER.get(), furnace, 90, 1.0, 0.2, random);
            burst(ModParticles.BLAZE_FLAME.get(), furnace, 40, 0.6, 0.1, random);
            burst(ModParticles.CINDER_SMOKE.get(), body, 30, 0.3, 0.05, random);
            debris(boss.position().add(0, 0.6, 0), 30, 0.5, random);
            shake(boss.position(), 1.4F, 24);
        }
    }

    // ===== 地面特效 =====

    // 特效实体在客户端出现时可能已过一两刻，从第 0 刻起补跑
    public static void effectTick(MagmaEffect effect) {
        int now = (int) effect.age(0);
        int from = Math.max(0, now - 3);
        if (effect.clientFxAge != Integer.MIN_VALUE) {
            from = Math.max(effect.clientFxAge + 1, now - 3);
        }
        for (int age = from; age <= now; age++) {
            effectTickAt(effect, age);
        }
        effect.clientFxAge = now;
    }

    private static void effectTickAt(MagmaEffect effect, int age) {
        RandomSource random = effect.level().random;
        Vec3 center = effect.position();
        var d = effect.data();
        switch (effect.kind()) {
            case MagmaEffect.SHOCKWAVE -> {
                float radius = d.getFloat("R");
                if (age == 0) {
                    spawn(ModParticles.HEAT_FLASH.get(), center.add(0, 0.4, 0), radius * 0.7, 0, 0);
                    debris(center, (int) (radius * 6), radius * 0.09, random);
                    burst(ModParticles.EMBER.get(), center.add(0, 0.3, 0), (int) (radius * 10), radius * 0.12, 0.25, random);
                    shake(center, 0.35F + radius * 0.14F, radius * 4 + 6);
                }
                // 烟尘沿扩张的环外缘翻起
                if (age < 10) {
                    float r = radius * Math.min(1.0F, (age + 1) / 9.0F);
                    int count = (int) (r * 3) + 2;
                    for (int i = 0; i < count; i++) {
                        double angle = random.nextDouble() * Math.PI * 2;
                        Vec3 at = center.add(Math.cos(angle) * r, 0.15, Math.sin(angle) * r);
                        spawn(ModParticles.CINDER_SMOKE.get(), at, Math.cos(angle) * 0.08, 0.04, Math.sin(angle) * 0.08);
                        if (d.getInt("Style") == 1) {
                            spawn(ModParticles.BLAZE_FLAME.get(), at, Math.cos(angle) * 0.1, 0.05, Math.sin(angle) * 0.1);
                        }
                    }
                }
            }
            case MagmaEffect.FISSURE -> {
                int lines = d.getInt("Lines");
                int segments = d.getInt("Segments");
                for (int line = 0; line < lines; line++) {
                    for (int k = 0; k < segments; k++) {
                        float dy = effect.groundOffset(line * segments + k);
                        if (Float.isNaN(dy)) {
                            break;
                        }
                        Vec3 point = center.add(effect.fissurePoint(line, k)).add(0, dy, 0);
                        int erupt = MagmaEffect.fissureEruptAge(k);
                        // 裂缝推进时冒火星，喷发瞬间火柱与碎石
                        if (age == (int) (k * 10.0F / segments)) {
                            spawn(ModParticles.EMBER.get(), point.add(0, 0.1, 0), spread(random, 0.05), 0.12, spread(random, 0.05));
                        }
                        if (age == erupt) {
                            fountain(point, 7, 0.55, random);
                            debris(point, 2, 0.12, random);
                            spawn(ModParticles.CINDER_SMOKE.get(), point.add(0, 0.6, 0), 0, 0.08, 0);
                        } else if (age > erupt && age < erupt + 8) {
                            spawn(ModParticles.BLAZE_FLAME.get(), point.add(spread(random, 0.3), 0.2, spread(random, 0.3)), 0, 0.22, 0);
                        }
                    }
                }
                if (age == MagmaEffect.fissureEruptAge(0)) {
                    shake(center, 0.5F, 16);
                }
            }
            case MagmaEffect.GEYSERS -> {
                int delay = d.getInt("Delay");
                for (int i = 0; i < d.getInt("Count"); i++) {
                    float dy = effect.groundOffset(i);
                    if (Float.isNaN(dy)) {
                        continue;
                    }
                    Vec3 point = center.add(effect.geyserPoint(i)).add(0, dy, 0);
                    // 预警冒泡，喷发巨型火柱
                    if (age < delay && random.nextFloat() < 0.5F) {
                        spawn(ModParticles.EMBER.get(), point.add(spread(random, 0.5), 0.1, spread(random, 0.5)), 0, 0.1, 0);
                    }
                    if (age == delay) {
                        spawn(ModParticles.HEAT_FLASH.get(), point.add(0, 0.5, 0), 1.8, 0, 0);
                        fountain(point, 14, 0.9, random);
                        debris(point, 4, 0.15, random);
                    } else if (age > delay && age < delay + 14) {
                        fountain(point, 2, 0.75, random);
                    }
                }
                if (age == delay) {
                    shake(center, 0.7F, 18);
                }
            }
            case MagmaEffect.POOL -> {
                float radius = d.getFloat("R");
                // 熔岩池冒泡、窜火与青烟
                if (age < effect.life() - 15) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double r = random.nextDouble() * radius * 0.9;
                    Vec3 at = center.add(Math.cos(angle) * r, 0.1, Math.sin(angle) * r);
                    if (random.nextFloat() < 0.5F) {
                        spawn(ModParticles.EMBER.get(), at, 0, 0.1 + random.nextDouble() * 0.1, 0);
                    }
                    if (random.nextFloat() < 0.25F) {
                        spawn(ModParticles.BLAZE_FLAME.get(), at, 0, 0.06, 0);
                    }
                    if (random.nextFloat() < 0.08F) {
                        spawn(ModParticles.CINDER_SMOKE.get(), at.add(0, 0.3, 0), 0, 0.03, 0);
                    }
                }
            }
            case MagmaEffect.NOVA -> {
                float radius = effect.novaRadius(age);
                if (radius > d.getFloat("R")) {
                    return;
                }
                if (age == 0) {
                    shake(center, 1.0F, 20);
                }
                // 火墙前沿持续窜火
                int count = (int) (radius * 2.2F) + 4;
                for (int i = 0; i < count; i++) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    Vec3 at = center.add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
                    spawn(ModParticles.BLAZE_FLAME.get(), at, Math.cos(angle) * 0.05, 0.12 + random.nextDouble() * 0.08, Math.sin(angle) * 0.05);
                    if (random.nextFloat() < 0.4F) {
                        spawn(ModParticles.EMBER.get(), at, Math.cos(angle) * 0.12, 0.2, Math.sin(angle) * 0.12);
                    }
                }
            }
            default -> {
            }
        }
    }

    // ===== 熔岩弹 =====

    public static void bombTick(LavaBomb bomb) {
        RandomSource random = bomb.level().random;
        if (!bomb.impacted()) {
            // 飞行拖尾：烟、火、火星
            Vec3 at = bomb.position();
            spawn(ModParticles.CINDER_SMOKE.get(), at, spread(random, 0.02), 0.02, spread(random, 0.02));
            spawn(ModParticles.BLAZE_FLAME.get(), at.add(spread(random, 0.15), spread(random, 0.15), spread(random, 0.15)), 0, 0, 0);
            spawn(ModParticles.EMBER.get(), at, spread(random, 0.08), spread(random, 0.08), spread(random, 0.08));
            return;
        }
        if (bomb.clientImpactDone) {
            return;
        }
        // 命中只播放一次爆裂
        bomb.clientImpactDone = true;
        Vec3 at = bomb.landing().add(0, 0.2, 0);
        spawn(ModParticles.HEAT_FLASH.get(), at.add(0, 0.3, 0), 2.6, 0, 0);
        debris(at, 12, 0.22, random);
        burst(ModParticles.EMBER.get(), at, 24, 0.45, 0.25, random);
        burst(ModParticles.BLAZE_FLAME.get(), at, 12, 0.25, 0.12, random);
        for (int i = 0; i < 6; i++) {
            spawn(ModParticles.CINDER_SMOKE.get(), at.add(spread(random, 0.8), 0.3, spread(random, 0.8)), spread(random, 0.05), 0.08, spread(random, 0.05));
        }
        shake(at, 0.4F, 12);
    }
}
