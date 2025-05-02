package ru.dimaskama.schematicpreview;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringIdentifiable;

import java.util.List;

public record ItemIconState(
        String itemId,
        Pos pos
) {

    public static final Codec<ItemIconState> CODEC = Codec.withAlternative(
            RecordCodecBuilder.create(inst -> inst.group(
                    Codec.STRING.fieldOf("itemId").forGetter(ItemIconState::itemId),
                    Pos.CODEC.fieldOf("pos").forGetter(ItemIconState::pos)
            ).apply(inst, ItemIconState::new)),
            Codec.STRING.xmap(ItemIconState::new, ItemIconState::itemId)
    );

    public ItemIconState(String itemId) {
        this(itemId, Pos.DEFAULT);
    }

    public enum Pos implements StringIdentifiable {

        DEFAULT("default"),
        CENTER("center"),
        DEFAULT_WITH_SCHEMATIC("default with schematic");

        public static final Codec<Pos> CODEC = StringIdentifiable.createBasicCodec(Pos::values);
        public static final List<Pos> VALUES = List.of(DEFAULT, CENTER, DEFAULT_WITH_SCHEMATIC);
        private final String key;

        Pos(String key) {
            this.key = key;
        }

        public Pos next() {
            return VALUES.get((VALUES.indexOf(this) + 1) % VALUES.size());
        }

        @Override
        public String asString() {
            return key;
        }

    }

}
