package threed_map.common.mark;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum Visibility implements StringRepresentable {
    PUBLIC("public"),
    PRIVATE("private");

    public static final Codec<Visibility> CODEC = StringRepresentable.fromEnum(Visibility::values);

    private final String name;

    Visibility(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
