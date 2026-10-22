package me.cinematic;

import me.cinematic.block.ScreenBlockEntity;
import me.cinematic.block.ScreenControllerBlock;
import me.cinematic.network.CinemaNetwork;
import me.cinematic.server.CinemaServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Cinematic.ID)
public final class Cinematic {
    public static final String ID = "cinematic";
    public static final Logger LOG = LoggerFactory.getLogger(ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> MEDIA_SOUND = SOUNDS.register("media", () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(ID, "media")));
    public static final DeferredBlock<ScreenControllerBlock> CONTROLLER = BLOCKS.registerBlock(
            "screen_controller", ScreenControllerBlock::new, p -> p.mapColor(MapColor.COLOR_BLACK).strength(2).noOcclusion());
    public static final DeferredItem<BlockItem> CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(CONTROLLER);
    public static final DeferredItem<Item> REMOTE = ITEMS.registerSimpleItem("remote", p -> p.stacksTo(1));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN_ENTITY = BLOCK_ENTITIES.register(
            "screen_controller", () -> new BlockEntityType<>(ScreenBlockEntity::new, CONTROLLER.get()));
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("cinematic", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.cinematic"))
            .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .icon(() -> CONTROLLER_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(CONTROLLER_ITEM.get());
                output.accept(REMOTE.get());
            }).build());

    public Cinematic(IEventBus bus, ModContainer container) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
        SOUNDS.register(bus);
        CinemaGameTests.FUNCTIONS.register(bus);
        container.registerConfig(ModConfig.Type.LOCAL, CinemaConfig.SPEC);
        bus.addListener(CinemaNetwork::register);
        CinemaServer.register(NeoForge.EVENT_BUS);
    }
}
