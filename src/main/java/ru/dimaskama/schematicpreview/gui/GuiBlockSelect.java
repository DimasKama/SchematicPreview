package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class GuiBlockSelect extends GuiBase {

    private static final Identifier UNKNOWN_SPRITE_ID = SchematicPreview.id("unknown");
    private static final int WIDTH = 200;
    private static final int HEIGHT = 200;
    private static final int ROWS = 7;
    private static final int COLUMNS = 10;
    private static final int ITEMS_START_Y = 38;
    private final Consumer<Block> blockConsumer;
    private final GuiTextFieldGeneric searchField;
    @Nullable
    private Block selectedBlock;
    protected int x = 0;
    protected int y = 0;
    private List<Block> blocks;
    private int rowIndex = 0;
    private int selectedBlockIndex;

    public GuiBlockSelect(@Nullable Screen parent, String title, @Nullable Block initialBlock, Consumer<Block> blockConsumer) {
        super();
        selectedBlock = initialBlock;
        this.blockConsumer = blockConsumer;
        this.title = title;
        setParent(parent);
        useTitleHierarchy = false;
        updateBlockList("");
        searchField = new GuiTextFieldGeneric(0, 0, WIDTH - 4, 20, textRenderer);
        searchField.setPlaceholder(Text.translatable("gui.schematicpreview.block_select.search").styled(s -> s.withColor(Formatting.GRAY).withItalic(true)));
        searchField.setMaxLengthWrapper(50);
        searchField.setChangedListener(this::updateBlockList);
    }

    private void updateBlockList(String searchInput) {
        rowIndex = 0;
        boolean searchInputBlank = searchInput.isBlank();
        if (!searchInputBlank) {
            searchInput = searchInput.trim().toLowerCase(Locale.ROOT);
        }
        String finalSearchInput = searchInput;
        blocks = Registries.BLOCK
                .streamEntries()
                .filter(ref -> searchInputBlank
                                || ref.getKey().get().toString().contains(finalSearchInput)
                                || StringUtils.translate(ref.value().getTranslationKey()).contains(finalSearchInput))
                .map(RegistryEntry.Reference::value)
                .sorted(Comparator.comparing(block -> StringUtils.translate(block.getTranslationKey())))
                .toList();
        selectedBlockIndex = blocks.indexOf(selectedBlock);
        movePageTo(selectedBlockIndex);
    }

    private void selectBlock(int index) {
        selectedBlockIndex = index;
        selectedBlock = blocks.get(index);
        movePageTo(index);
    }

    private void movePageTo(int index) {
        if (index != -1) {
            int newRowIndex = rowIndex;
            int itemsOnPage = ROWS * COLUMNS;
            while (index >= newRowIndex * COLUMNS + itemsOnPage) {
                ++newRowIndex;
            }
            while (index < newRowIndex * COLUMNS) {
                --newRowIndex;
            }
            rowIndex = newRowIndex;
        }
    }

    private int getBlockIndexAtUnchecked(double mouseX, double mouseY) {
        int column = MathHelper.floor((mouseX - x) / 20.0);
        int row = MathHelper.floor((mouseY - y - ITEMS_START_Y) / 20.0);
        return column >= 0 && column < COLUMNS && row >= 0 && row < ROWS ? (rowIndex + row) * COLUMNS + column :-1;
    }

    private int getBlockIndexAt(double mouseX, double mouseY) {
        int i = getBlockIndexAtUnchecked(mouseX, mouseY);
        return i < blocks.size() ? i : -1;
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers) {
        return searchField.isFocused() ? searchField.keyPressed(keyCode, scanCode, modifiers) : super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    public boolean onCharTyped(char charIn, int modifiers) {
        return searchField.isFocused() ? searchField.charTyped(charIn, modifiers) : super.onCharTyped(charIn, modifiers);
    }

    public boolean onMouseClicked(int mouseX, int mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            int hoveredBlockIndex = getBlockIndexAt(mouseX, mouseY);
            if (hoveredBlockIndex != -1) {
                selectBlock(hoveredBlockIndex);
            }
        }
        return searchField.mouseClicked(mouseX, mouseY, button) || super.onMouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean onMouseScrolled(int mouseX, int mouseY, double horizontalAmount, double verticalAmount) {
        if (getBlockIndexAtUnchecked(mouseX, mouseY) != -1) {
            int newRowIndex = rowIndex + (verticalAmount < 0.0 ? 1 : -1);
            if (newRowIndex >= 0 && newRowIndex * COLUMNS < blocks.size()) {
                rowIndex = newRowIndex;
            }
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void initGui() {
        super.initGui();
        x = (width - WIDTH) >> 1;
        y = (height - HEIGHT) >> 1;
        addButton(new ButtonGeneric(x + 2, y + HEIGHT - 22, 98, 20, ScreenTexts.OK.getString()), (buttonBase, i) -> {
            if (selectedBlock != null) {
                closeGui(true);
                blockConsumer.accept(selectedBlock);
            }
        });
        addButton(new ButtonGeneric(x + 101, y + HEIGHT - 22, 97, 20, ScreenTexts.CANCEL.getString()), (buttonBase, i) -> {
            closeGui(true);
        });
        searchField.setX(x + 2);
        searchField.setY(y + 15);
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        if (getParent() != null) {
            getParent().render(drawContext, mouseX, mouseY, partialTicks);
        }

        MatrixStack matrixStack = drawContext.getMatrices();
        matrixStack.push();
        matrixStack.translate(0.0F, 0.0F, 1.0F);
        RenderUtils.drawOutlinedBox(x, y, WIDTH, HEIGHT, 0xAA000000, 0xFFFFFFFF);
        String title = getTitleString();
        drawStringWithShadow(drawContext, title, x + ((WIDTH - textRenderer.getWidth(title)) >> 1), y + 4, 0xFFFFFFFF);

        drawWidgets(mouseX, mouseY, drawContext);
        drawButtons(mouseX, mouseY, partialTicks, drawContext);

        int hoveredBlockIndex = getBlockIndexAt(mouseX, mouseY);

        int firstIndex = rowIndex * COLUMNS;
        int endIndex = firstIndex + ROWS * COLUMNS;
        for (int i = firstIndex; i < endIndex; i++) {
            if (i < blocks.size()) {
                int pageI = i - firstIndex;
                int itemX = x + (pageI % COLUMNS * 20) + 2;
                int itemY = y + ITEMS_START_Y + (pageI / COLUMNS * 20) + 2;
                RenderUtils.drawOutlinedBox(
                        itemX, itemY,
                        16, 16,
                        0x22FFFFFF,
                        i == selectedBlockIndex ? 0xFFFFFFFF : i == hoveredBlockIndex ? 0xAAFFFFFF : 0x22FFFFFF
                );
                Block block = blocks.get(i);
                Item item = block.asItem();
                if (item == Items.AIR && block != Blocks.AIR) {
                    drawContext.drawGuiTexture(UNKNOWN_SPRITE_ID, itemX, itemY, 0, 16, 16);
                } else {
                    drawContext.drawItem(item.getDefaultStack(), itemX, itemY);
                }
            }
        }

        searchField.render(drawContext, mouseX, mouseY, partialTicks);

        if (hoveredBlockIndex != -1) {
            Block hoveredBlock = blocks.get(hoveredBlockIndex);
            RenderUtils.drawHoverText(mouseX, mouseY, List.of(
                    StringUtils.translate(hoveredBlock.getTranslationKey()),
                    String.valueOf(Formatting.FORMATTING_CODE_PREFIX) + Formatting.DARK_GRAY.getCode() + Registries.BLOCK.getId(hoveredBlock).toString()
            ), drawContext);
        }

        matrixStack.pop();
    }

}
