package org.hp.cindermaw.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.hp.cindermaw.ModEntities;
import org.hp.cindermaw.client.CinderFx;

import java.util.HashSet;
import java.util.Set;

// 一个实体承载一次地面特效：冲击环、熔岩裂缝、喷泉环、熔岩池或新星火墙。
public final class MagmaEffect extends Projectile {
    public static final int SHOCKWAVE = 1;
    public static final int FISSURE = 2;
    public static final int GEYSERS = 3;
    public static final int POOL = 4;
    public static final int NOVA = 5;
    public static final int NO_GROUND = -9999;
    public static final float SEGMENT = 1.0F;

    private static final EntityDataAccessor<CompoundTag> DATA = SynchedEntityData.defineId(MagmaEffect.class, EntityDataSerializers.COMPOUND_TAG);
    private final Set<Integer> struck = new HashSet<>();
    public int clientFxAge = Integer.MIN_VALUE;

    public MagmaEffect(EntityType<? extends MagmaEffect> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA, new CompoundTag());
    }

    // 同步数据读取
    public CompoundTag data() {
        return entityData.get(DATA);
    }

    public boolean ready() {
        return !data().isEmpty();
    }

    public int kind() {
        return data().getInt("Kind");
    }

    public float age(float partial) {
        return (int) level().getGameTime() - data().getInt("Start") + partial;
    }

    public int life() {
        return data().getInt("Life");
    }

    // 地面高度偏移：以 1/16 格保存，NO_GROUND 表示悬空
    public float groundOffset(int index) {
        int[] heights = data().getIntArray("H");
        if (index < 0 || index >= heights.length || heights[index] == NO_GROUND) {
            return Float.NaN;
        }
        return heights[index] / 16.0F;
    }

    // 裂缝第 line 条第 k 段的水平偏移
    public Vec3 fissurePoint(int line, int k) {
        CompoundTag d = data();
        int lines = d.getInt("Lines");
        float spread = d.getFloat("Spread");
        float yaw = d.getFloat("Yaw");
        float offset = 0;
        if (lines > 1) {
            offset = -spread * 0.5F + spread * line / (lines - 1);
        }
        float angle = (yaw + offset) * Mth.DEG_TO_RAD;
        float distance = 0.8F + k * SEGMENT;
        return new Vec3(-Mth.sin(angle) * distance, 0, Mth.cos(angle) * distance);
    }

    // 裂缝每段的喷发时刻：从中心向外推进
    public static int fissureEruptAge(int k) {
        return 12 + k * 3 / 2;
    }

    // 喷泉环第 i 个喷口的水平偏移
    public Vec3 geyserPoint(int i) {
        CompoundTag d = data();
        float angle = (float) (i * Math.PI * 2 / d.getInt("Count")) + d.getFloat("Phase");
        float radius = d.getFloat("R");
        return new Vec3(Mth.cos(angle) * radius, 0, Mth.sin(angle) * radius);
    }

    // 新星火墙当前半径
    public float novaRadius(float age) {
        return 1.0F + age * 0.38F;
    }

    // 生成效果实体并写入初始数据
    private static MagmaEffect spawn(LivingEntity owner, Vec3 at, CompoundTag d, int life) {
        MagmaEffect effect = ModEntities.MAGMA_EFFECT.get().create(owner.level());
        if (effect == null) {
            return null;
        }
        effect.setOwner(owner);
        effect.setPos(at.x, at.y, at.z);
        d.putInt("Start", (int) owner.level().getGameTime());
        d.putInt("Life", life);
        effect.entityData.set(DATA, d);
        owner.level().addFreshEntity(effect);
        return effect;
    }

    // 地面高度采样：从上往下找支撑面
    private static int sampleGround(LivingEntity owner, Vec3 point) {
        var hit = owner.level().clip(new ClipContext(point.add(0, 2.5, 0), point.add(0, -4.0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) {
            return NO_GROUND;
        }
        return Math.round((float) (hit.getLocation().y - point.y) * 16.0F);
    }

    public static void shockwave(LivingEntity owner, Vec3 at, float radius, int style) {
        CompoundTag d = new CompoundTag();
        d.putInt("Kind", SHOCKWAVE);
        d.putFloat("R", radius);
        d.putInt("Style", style);
        spawn(owner, at, d, 26);
    }

    public static void fissure(CindermawEntity owner, Vec3 at, float yaw, int lines, float length) {
        fissure(owner, at, yaw, lines, length, owner.attackDamage() * 0.55F);
    }

    // 通用裂缝：施放者可以是 Boss 或手持熔渊锤的玩家，每段伤害单独指定
    public static void fissure(LivingEntity owner, Vec3 at, float yaw, int lines, float length, float damage) {
        CompoundTag d = new CompoundTag();
        d.putFloat("Damage", damage);
        d.putInt("Kind", FISSURE);
        d.putFloat("Yaw", yaw);
        d.putInt("Lines", lines);
        d.putFloat("Spread", 18.0F * (lines - 1));
        int segments = (int) (length / SEGMENT);
        d.putInt("Segments", segments);
        // 逐段采样地面，遇到悬空后整条裂缝截断
        int[] heights = new int[lines * segments];
        for (int line = 0; line < lines; line++) {
            boolean broken = false;
            for (int k = 0; k < segments; k++) {
                int index = line * segments + k;
                if (broken) {
                    heights[index] = NO_GROUND;
                    continue;
                }
                float angle = (yaw - 9.0F * (lines - 1) + 18.0F * line) * Mth.DEG_TO_RAD;
                float distance = 0.8F + k * SEGMENT;
                Vec3 point = at.add(-Mth.sin(angle) * distance, 0, Mth.cos(angle) * distance);
                heights[index] = sampleGround(owner, point);
                if (heights[index] == NO_GROUND || Math.abs(heights[index]) > 32) {
                    heights[index] = NO_GROUND;
                    broken = true;
                }
            }
        }
        d.putInt("Lines", lines);
        d.putIntArray("H", heights);
        spawn(owner, at, d, fissureEruptAge(segments) + 18);
    }

    public static void geyserRing(CindermawEntity owner, Vec3 at, float radius, int count, int delay) {
        CompoundTag d = new CompoundTag();
        d.putInt("Kind", GEYSERS);
        d.putFloat("R", radius);
        d.putInt("Count", count);
        d.putInt("Delay", delay);
        d.putFloat("Phase", owner.getRandom().nextFloat() * 6.28F);
        // 每个喷口单独贴地
        int[] heights = new int[count];
        for (int i = 0; i < count; i++) {
            float angle = (float) (i * Math.PI * 2 / count) + d.getFloat("Phase");
            heights[i] = sampleGround(owner, at.add(Mth.cos(angle) * radius, 0, Mth.sin(angle) * radius));
        }
        d.putIntArray("H", heights);
        spawn(owner, at, d, delay + 24);
    }

    public static void pool(CindermawEntity owner, Vec3 at, float radius) {
        CompoundTag d = new CompoundTag();
        d.putInt("Kind", POOL);
        d.putFloat("R", radius);
        spawn(owner, at, d, 80);
    }

    public static void nova(CindermawEntity owner, Vec3 at, float maxRadius) {
        CompoundTag d = new CompoundTag();
        d.putInt("Kind", NOVA);
        d.putFloat("R", maxRadius);
        spawn(owner, at, d, (int) ((maxRadius - 1.0F) / 0.38F) + 10);
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
        // 客户端只负责特效
        if (level().isClientSide) {
            if (ready()) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CinderFx.effectTick(this));
            }
            return;
        }
        if (!ready()) {
            discard();
            return;
        }
        int age = (int) age(0);
        if (age > life()) {
            discard();
            return;
        }
        if (!(getOwner() instanceof LivingEntity caster)) {
            return;
        }
        // 裂缝允许任意施放者，其余效果只属于 Boss
        if (kind() == FISSURE) {
            tickFissure(caster, age);
            return;
        }
        if (!(caster instanceof CindermawEntity owner)) {
            return;
        }
        // 按类型结算伤害
        switch (kind()) {
            case GEYSERS -> tickGeysers(owner, age);
            case POOL -> tickPool(owner, age);
            case NOVA -> tickNova(owner, age);
            default -> {
            }
        }
    }

    // 裂缝：每段在喷发刻对附近目标结算一次
    private void tickFissure(LivingEntity owner, int age) {
        CompoundTag d = data();
        int lines = d.getInt("Lines");
        int segments = d.getInt("Segments");
        for (int line = 0; line < lines; line++) {
            for (int k = 0; k < segments; k++) {
                if (fissureEruptAge(k) != age) {
                    continue;
                }
                float dy = groundOffset(line * segments + k);
                if (Float.isNaN(dy)) {
                    continue;
                }
                Vec3 point = position().add(fissurePoint(line, k)).add(0, dy, 0);
                strike(owner, point, 1.0, 2.6, d.getFloat("Damage"), 4, 0.75);
                if (k % 3 == 0) {
                    level().playSound(null, point.x, point.y, point.z, SoundEvents.LAVA_POP, SoundSource.HOSTILE, 1.4F, 0.6F);
                }
            }
        }
    }

    // 喷泉环：延迟结束时一起喷发
    private void tickGeysers(CindermawEntity owner, int age) {
        CompoundTag d = data();
        if (age != d.getInt("Delay")) {
            return;
        }
        for (int i = 0; i < d.getInt("Count"); i++) {
            float dy = groundOffset(i);
            if (Float.isNaN(dy)) {
                continue;
            }
            Vec3 point = position().add(geyserPoint(i)).add(0, dy, 0);
            strike(owner, point, 1.2, 3.2, owner.attackDamage() * 0.75F, 4, 0.95);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.9F);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.LAVA_POP, SoundSource.HOSTILE, 2.0F, 0.5F);
    }

    // 熔岩池：每半秒灼烧站在池中的目标，可以重复命中
    private void tickPool(CindermawEntity owner, int age) {
        if (age % 10 != 5 || age > life() - 10) {
            return;
        }
        float radius = data().getFloat("R");
        for (LivingEntity victim : level().getEntitiesOfClass(LivingEntity.class, new AABB(position(), position()).inflate(radius, 1.0, radius))) {
            Vec3 offset = victim.position().subtract(position());
            if (!owner.canHarm(victim) || offset.horizontalDistance() > radius || offset.y < -0.6 || offset.y > 0.8) {
                continue;
            }
            victim.invulnerableTime = 0;
            if (victim.hurt(damageSources().mobProjectile(this, owner), owner.attackDamage() * 0.12F)) {
                victim.setSecondsOnFire(3);
            }
        }
    }

    // 新星：火墙经过时脚下没有跳起的目标被击中，每个目标只命中一次
    private void tickNova(CindermawEntity owner, int age) {
        float radius = novaRadius(age);
        float max = data().getFloat("R");
        if (radius > max) {
            return;
        }
        for (LivingEntity victim : level().getEntitiesOfClass(LivingEntity.class, new AABB(position(), position()).inflate(radius + 1.0, 3.0, radius + 1.0))) {
            Vec3 offset = victim.position().subtract(position());
            double distance = offset.horizontalDistance();
            if (struck.contains(victim.getId()) || !owner.canHarm(victim) || distance < radius - 0.8 || distance > radius + 0.3) {
                continue;
            }
            if (offset.y > 0.9 || offset.y < -2.0) {
                continue;
            }
            struck.add(victim.getId());
            if (victim.hurt(damageSources().mobProjectile(this, owner), owner.attackDamage() * 0.8F)) {
                victim.setSecondsOnFire(4);
                CindermawEntity.shove(victim, offset, 0.8, 0.3);
            }
        }
    }

    // 单点结算：水平半径与高度范围内，同一效果内每个目标只命中一次
    // 判断施放者能否伤害目标：Boss 走自身规则，玩家不伤自己、队友和自己的宠物
    private static boolean harms(LivingEntity owner, LivingEntity victim) {
        if (owner instanceof CindermawEntity boss) {
            return boss.canHarm(victim);
        }
        if (victim == owner || !victim.isAlive() || owner.isAlliedTo(victim)) {
            return false;
        }
        return !(victim instanceof TamableAnimal pet && pet.isOwnedBy(owner));
    }

    private void strike(LivingEntity owner, Vec3 point, double radius, double height, float damage, int fire, double lift) {
        for (LivingEntity victim : level().getEntitiesOfClass(LivingEntity.class, new AABB(point, point).inflate(radius + 0.5, height, radius + 0.5))) {
            Vec3 offset = victim.position().subtract(point);
            if (struck.contains(victim.getId()) || !harms(owner, victim)) {
                continue;
            }
            if (offset.horizontalDistance() > radius + victim.getBbWidth() * 0.5 || offset.y < -0.6 || offset.y > height) {
                continue;
            }
            struck.add(victim.getId());
            if (victim.hurt(damageSources().mobProjectile(this, owner), damage)) {
                victim.setSecondsOnFire(fire);
                victim.setDeltaMovement(victim.getDeltaMovement().add(0, lift, 0));
                victim.hurtMarked = true;
            }
        }
    }

    // 特效不需要持久化
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    // 短寿命实体不写入存档
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96 * 96;
    }
}
