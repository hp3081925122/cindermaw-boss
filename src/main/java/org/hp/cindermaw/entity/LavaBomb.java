package org.hp.cindermaw.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.hp.cindermaw.client.CinderFx;

// 火山齐射的熔岩弹：发射时确定抛物线，落点提前显示预警，命中后爆炸并留下熔岩池。
public final class LavaBomb extends Projectile {
    private static final EntityDataAccessor<CompoundTag> FLIGHT = SynchedEntityData.defineId(LavaBomb.class, EntityDataSerializers.COMPOUND_TAG);
    public static final int LINGER = 4;
    public int clientFxAge = Integer.MIN_VALUE;
    public boolean clientImpactDone;

    public LavaBomb(EntityType<? extends LavaBomb> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(FLIGHT, new CompoundTag());
    }

    // 写入弹道：起点、终点、起始刻与飞行时长
    public void launch(CindermawEntity owner, Vec3 start, Vec3 end, int ticks) {
        setOwner(owner);
        CompoundTag d = new CompoundTag();
        d.putDouble("SX", start.x);
        d.putDouble("SY", start.y);
        d.putDouble("SZ", start.z);
        d.putDouble("EX", end.x);
        d.putDouble("EY", end.y);
        d.putDouble("EZ", end.z);
        d.putInt("Start", (int) owner.level().getGameTime());
        d.putInt("Ticks", ticks);
        entityData.set(FLIGHT, d);
        setPos(start.x, start.y, start.z);
    }

    // 弹道只读采样，客户端用同一条曲线绘制尾迹
    public boolean ready() {
        return !entityData.get(FLIGHT).isEmpty();
    }

    public int flightTicks() {
        return entityData.get(FLIGHT).getInt("Ticks");
    }

    public float age(float partial) {
        return (int) level().getGameTime() - entityData.get(FLIGHT).getInt("Start") + partial;
    }

    public boolean impacted() {
        return entityData.get(FLIGHT).getBoolean("Hit");
    }

    public float impactAge(float partial) {
        return (int) level().getGameTime() - entityData.get(FLIGHT).getInt("HitTime") + partial;
    }

    public Vec3 landing() {
        CompoundTag d = entityData.get(FLIGHT);
        return new Vec3(d.getDouble("EX"), d.getDouble("EY"), d.getDouble("EZ"));
    }

    public Vec3 sample(float age) {
        CompoundTag d = entityData.get(FLIGHT);
        Vec3 start = new Vec3(d.getDouble("SX"), d.getDouble("SY"), d.getDouble("SZ"));
        double t = Math.max(0, Math.min(1, age / flightTicks()));
        double arc = 6.0 + start.distanceTo(landing()) * 0.35;
        return start.add(landing().subtract(start).scale(t)).add(0, arc * 4 * t * (1 - t), 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!ready()) {
            if (!level().isClientSide) {
                discard();
            }
            return;
        }
        // 客户端只负责特效
        if (level().isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CinderFx.bombTick(this));
        }
        if (impacted()) {
            if (!level().isClientSide && impactAge(0) >= LINGER) {
                discard();
            }
            return;
        }
        Vec3 next = sample(age(0) + 1);
        if (!level().isClientSide) {
            // 按实际曲线逐段检测方块，屋顶和墙壁会提前引爆
            var hit = level().clip(new ClipContext(position(), next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
            if (hit.getType() != HitResult.Type.MISS || age(0) >= flightTicks()) {
                Vec3 contact = landing();
                if (hit.getType() != HitResult.Type.MISS) {
                    contact = hit.getLocation();
                }
                explode(contact);
                return;
            }
        }
        setDeltaMovement(next.subtract(position()));
        setPos(next.x, next.y, next.z);
    }

    // 爆炸：一次性伤害，生成熔岩池，标记命中并保留数刻给客户端播放爆裂
    private void explode(Vec3 contact) {
        setPos(contact.x, contact.y, contact.z);
        CompoundTag d = entityData.get(FLIGHT).copy();
        d.putBoolean("Hit", true);
        d.putInt("HitTime", (int) level().getGameTime());
        d.putDouble("EX", contact.x);
        d.putDouble("EY", contact.y);
        d.putDouble("EZ", contact.z);
        entityData.set(FLIGHT, d);
        level().playSound(null, contact.x, contact.y, contact.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6F, 1.1F);
        level().playSound(null, contact.x, contact.y, contact.z, SoundEvents.LAVA_POP, SoundSource.HOSTILE, 1.5F, 0.6F);
        if (!(getOwner() instanceof CindermawEntity owner) || !owner.isAlive()) {
            return;
        }
        for (LivingEntity victim : level().getEntitiesOfClass(LivingEntity.class, new AABB(contact, contact).inflate(2.6))) {
            Vec3 center = victim.getBoundingBox().getCenter();
            if (!owner.canHarm(victim) || center.distanceToSqr(contact) > 2.4 * 2.4 || !owner.clearPath(contact.add(0, 0.3, 0), victim)) {
                continue;
            }
            if (victim.hurt(damageSources().mobProjectile(this, owner), owner.attackDamage() * 0.7F)) {
                victim.setSecondsOnFire(3);
                CindermawEntity.shove(victim, victim.position().subtract(contact), 0.5, 0.3);
            }
        }
        // 熔岩池贴到地面，命中半空目标时也落在脚下
        MagmaEffect.pool(owner, owner.groundAt(contact), 1.6F);
    }

    // 熔岩弹不持久化
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
        return distance < 128 * 128;
    }
}
