package org.hp.cindermaw;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.hp.cindermaw.entity.CindermawEntity;
import org.hp.cindermaw.entity.LavaBomb;
import org.hp.cindermaw.entity.MagmaEffect;
import org.hp.cindermaw.item.CindermawArmorMaterial;
import org.hp.cindermaw.item.CindermawCoreItem;
import org.hp.cindermaw.item.CindermawHammerItem;

// 集中注册 Boss、熔岩弹、地面特效实体与刷怪蛋，公共注册类不引用客户端代码。
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Cindermaw.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Cindermaw.MOD_ID);

    // 碰撞体覆盖撑地后的双臂与躯干，免疫火焰与岩浆。
    public static final RegistryObject<EntityType<CindermawEntity>> CINDERMAW = ENTITIES.register("cindermaw",
            () -> EntityType.Builder.of(CindermawEntity::new, MobCategory.MONSTER)
                    .sized(3.2F, 3.3F).fireImmune().clientTrackingRange(10).updateInterval(2)
                    .build(Cindermaw.MOD_ID + ":cindermaw"));

    // 火山齐射的熔岩弹，弹道由服务端一次性确定。
    public static final RegistryObject<EntityType<LavaBomb>> LAVA_BOMB = ENTITIES.register("lava_bomb",
            () -> EntityType.Builder.<LavaBomb>of(LavaBomb::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F).fireImmune().clientTrackingRange(12).updateInterval(1)
                    .build(Cindermaw.MOD_ID + ":lava_bomb"));

    // 冲击环、熔岩裂缝、喷泉环、熔岩池与新星火墙共用一个短寿命实体。
    public static final RegistryObject<EntityType<MagmaEffect>> MAGMA_EFFECT = ENTITIES.register("magma_effect",
            () -> EntityType.Builder.<MagmaEffect>of(MagmaEffect::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F).fireImmune().clientTrackingRange(12).updateInterval(5)
                    .build(Cindermaw.MOD_ID + ":magma_effect"));

    // 刷怪蛋颜色对应玄武岩主体与炉心橙焰。
    public static final RegistryObject<Item> CINDERMAW_SPAWN_EGG = ITEMS.register("cindermaw_spawn_egg",
            () -> new ForgeSpawnEggItem(CINDERMAW, 0x252933, 0xE65A1B, new Item.Properties()));

    // 熔渊炉核：召唤 Boss 的消耗品，防火、史诗稀有度
    public static final RegistryObject<Item> CINDERMAW_CORE = ITEMS.register("cindermaw_core",
            () -> new CindermawCoreItem(new Item.Properties().stacksTo(16).rarity(Rarity.EPIC).fireResistant()));

    // 熔渊之心：Boss 必掉的核心材料
    public static final RegistryObject<Item> CINDERMAW_HEART = ITEMS.register("cindermaw_heart",
            () -> new Item(new Item.Properties().rarity(Rarity.EPIC).fireResistant()));

    // 熔渊锤：重型近战武器，蓄力砸地
    public static final RegistryObject<Item> CINDERMAW_HAMMER = ITEMS.register("cindermaw_hammer",
            () -> new CindermawHammerItem(new Item.Properties().rarity(Rarity.EPIC).fireResistant()));

    // 炉心胸甲：下界合金胸甲锻造升级
    public static final RegistryObject<Item> CINDERMAW_CHESTPLATE = ITEMS.register("cindermaw_chestplate",
            () -> new ArmorItem(CindermawArmorMaterial.INSTANCE, ArmorItem.Type.CHESTPLATE, new Item.Properties().rarity(Rarity.EPIC).fireResistant()));

    private ModEntities() {
    }
}
