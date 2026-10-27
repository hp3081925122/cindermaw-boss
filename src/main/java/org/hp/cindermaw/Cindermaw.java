package org.hp.cindermaw;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.hp.cindermaw.entity.CindermawEntity;

@Mod(Cindermaw.MOD_ID)
public final class Cindermaw {
    public static final String MOD_ID = "cindermaw";

    // 本模组专属创造物品栏：图标为熔渊炉核，收录全部物品
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cindermaw"))
            .icon(() -> ModEntities.CINDERMAW_CORE.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(ModEntities.CINDERMAW_CORE.get());
                output.accept(ModEntities.CINDERMAW_HEART.get());
                output.accept(ModEntities.CINDERMAW_HAMMER.get());
                output.accept(ModEntities.CINDERMAW_CHESTPLATE.get());
                output.accept(ModEntities.CINDERMAW_SPAWN_EGG.get());
            })
            .build());

    public Cindermaw(FMLJavaModLoadingContext context) {
        // 注册实体、物品、粒子类型与创造物品栏
        var bus = context.getModEventBus();
        ModEntities.ENTITIES.register(bus);
        ModEntities.ITEMS.register(bus);
        ModParticles.PARTICLES.register(bus);
        TABS.register(bus);
        bus.addListener(this::registerAttributes);
        bus.addListener(this::fillCreativeTab);
    }

    // 为 Boss 注册服务端属性，确保刷怪蛋和命令召唤拥有完整属性。
    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.CINDERMAW.get(), CindermawEntity.createAttributes().build());
    }

    // 刷怪蛋同时保留在原版生成蛋页
    private void fillCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModEntities.CINDERMAW_SPAWN_EGG);
        }
    }
}
