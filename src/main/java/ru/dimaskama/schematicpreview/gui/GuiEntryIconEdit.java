package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.gui.GuiTextInputBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.Optionull;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import ru.dimaskama.schematicpreview.ItemIconState;

public class GuiEntryIconEdit extends GuiTextInputBase {

    private final Feedback feedback;
    private String lastInput = "";
    private ItemIconState.Pos pos = ItemIconState.Pos.DEFAULT;

    public GuiEntryIconEdit(Screen parent, @Nullable ItemIconState oldState, Feedback feedback) {
        super(64, "gui.schematicpreview.change_directory_icon", Optionull.map(oldState, ItemIconState::itemId), parent);
        this.feedback = feedback;
        if (oldState != null) {
            lastInput = oldState.itemId();
            pos = oldState.pos();
        }
        setWidthAndHeight(260, 125);
    }

    @Override
    public void initGui() {
        int x = dialogLeft + 10;
        int y = dialogTop + 70;
        addButton(new ButtonGeneric(x, y, 240, 20, getPosButtonText()), (button, i) -> {
            pos = pos.next();
            button.setDisplayString(getPosButtonText());
            apply();
        });
        y += 25;
        x += createButton(x, y, GuiTextInputBase.ButtonType.OK) + 2;
        x += createButton(x, y, GuiTextInputBase.ButtonType.RESET) + 2;
        createButton(x, y, GuiTextInputBase.ButtonType.CANCEL);
    }

    private String getPosButtonText() {
        return I18n.get("gui.schematicpreview.change_directory_icon.pos", pos.getSerializedName());
    }

    @Override
    protected boolean applyValue(String s) {
        lastInput = s;
        return apply();
    }

    protected boolean apply() {
        return feedback.accept(new ItemIconState(lastInput, pos));
    }

    public interface Feedback {

        boolean accept(ItemIconState state);

    }

}
