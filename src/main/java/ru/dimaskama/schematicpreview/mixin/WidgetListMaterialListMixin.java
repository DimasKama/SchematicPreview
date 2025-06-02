package ru.dimaskama.schematicpreview.mixin;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetListMaterialList;
import fi.dy.masa.litematica.gui.widgets.WidgetMaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.SchematicMetadata;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.malilib.gui.GuiTextInput;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(WidgetListMaterialList.class)
abstract class WidgetListMaterialListMixin {

    @Shadow(remap = false) @Final private GuiMaterialList gui;

    @Inject(
            method = "createListEntryWidget(IIIZLfi/dy/masa/litematica/materials/MaterialListEntry;)Lfi/dy/masa/litematica/gui/widgets/WidgetMaterialListEntry;",
            at = @At("TAIL"),
            remap = false
    )
    private void modifyEntry(int x, int y, int listIndex, boolean isOdd, MaterialListEntry e, CallbackInfoReturnable<WidgetMaterialListEntry> cir) {
        if (gui.getMaterialList() instanceof MaterialListSchematic || gui.getMaterialList() instanceof MaterialListPlacement) {
            WidgetMaterialListEntry entry = cir.getReturnValue();
            String text = StringUtils.translate("gui.schematicpreview.replace_block");
            int w = MinecraftClient.getInstance().textRenderer.getWidth(text);
            ButtonGeneric button = new ButtonGeneric(entry.getX() + entry.getWidth() - w - 53, entry.getY() + ((entry.getHeight() - 20) >> 1), w + 8, 20, text);
            button.setActionListener((b, mouseButton) -> {
                if (e.getStack().getItem() instanceof BlockItem blockItem) {
                    Block oldBlock = blockItem.getBlock();
                    Identifier oldBlockId = Registries.BLOCK.getId(oldBlock);
                    MinecraftClient.getInstance().setScreen(new GuiTextInput(
                            250,
                            "gui.schematicpreview.replace_block.title",
                            oldBlockId.toString(),
                            MinecraftClient.getInstance().currentScreen,
                            s -> {
                                Identifier id = Identifier.tryParse(s);
                                Block newBlock;
                                if (id != null && !oldBlockId.equals(id) && ((newBlock = Registries.BLOCK.get(id)) != Blocks.AIR || id.equals(Identifier.ofVanilla("air")))) {
                                    int c = schematicpreview_replaceBlocks(oldBlock, newBlock, gui.getMaterialList());
                                    if (c > 0) {
                                        gui.getMaterialList().reCreateMaterialList();
                                    }
                                    InfoUtils.showInGameMessage(Message.MessageType.INFO, 20000L, "gui.schematicpreview.replace_block.result", c, StringUtils.translate(newBlock.getTranslationKey()));
                                } else {
                                    InfoUtils.showInGameMessage(Message.MessageType.WARNING, 20000L, "gui.schematicpreview.replace_block.invalid_block_id", s);
                                }
                            }
                    ));
                }
            });
            ((WidgetContainerInvoker) entry).schematicpreview_addWidget(button);
        }
    }

    @Unique
    private int schematicpreview_replaceBlocks(Block oldBlock, Block newBlock, MaterialListBase materialList) {
        return switch (materialList) {
            case MaterialListSchematic materialListSchematic -> {
                LitematicaSchematic schematic = ((MaterialListSchematicAccessor) materialListSchematic).schematicpreview_schematic();
                ImmutableList<String> regions = ((MaterialListSchematicAccessor) materialListSchematic).schematicpreview_regions();
                yield schematicpreview_replaceBlocks(oldBlock, newBlock, schematic, regions);
            }
            case MaterialListPlacement materialListPlacement -> {
                LitematicaSchematic schematic = ((MaterialListPlacementAccessor) materialListPlacement).schematicpreview_placement().getSchematic();
                yield schematicpreview_replaceBlocks(oldBlock, newBlock, schematic, schematic.getAreas().keySet());
            }
            case null, default -> 0;
        };
    }

    @Unique
    private int schematicpreview_replaceBlocks(Block oldBlock, Block newBlock, LitematicaSchematic schematic, Collection<String> regions) {
        int count = 0;
        BlockState newBlockState = newBlock.getDefaultState();
        Property<?>[] props = oldBlock.getDefaultState().getProperties().stream().filter(newBlockState::contains).toArray(Property<?>[]::new);
        for (String regionName : regions) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            if (container != null) {
                Vec3i size = container.getSize();
                int endY = size.getY();
                int endZ = size.getZ();
                int endX = size.getX();
                for (int y = 0; y < endY; y++) {
                    for (int z = 0; z < endZ; z++) {
                        for (int x = 0; x < endX; x++) {
                            BlockState oldState = container.get(x, y, z);
                            if (oldState.getBlock() == oldBlock) {
                                for (Property<?> prop : props) {
                                    newBlockState = schematicpreview_copyProp(newBlockState, prop, oldState.get(prop));
                                }
                                container.set(x, y, z, newBlockState);
                                ++count;
                            }
                        }
                    }
                }
            }
        }
        if (count > 0) {
            SchematicMetadata metadata = schematic.getMetadata();
            boolean oldAir = oldBlock.getDefaultState().isAir();
            boolean newAir = newBlock.getDefaultState().isAir();
            if (oldAir != newAir) {
                metadata.setTotalBlocks(metadata.getTotalBlocks() + count * (newAir ? -1 : 1));
            }
            metadata.setTimeModifiedToNow();
            metadata.setModifiedSinceSaved();
            DataManager.getSchematicPlacementManager().markAllPlacementsOfSchematicForRebuild(schematic);
        }
        return count;
    }

    @Unique
    private <T extends Comparable<T>> BlockState schematicpreview_copyProp(BlockState state, Property<?> prop, T value) {
        @SuppressWarnings("unchecked")
        Property<T> casted = (Property<T>) prop;
        return state.with(casted, value);
    }

}
