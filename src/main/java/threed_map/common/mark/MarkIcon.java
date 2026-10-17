package threed_map.common.mark;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum MarkIcon implements StringRepresentable {
    PIN("pin"),
    HOME("home"),
    STAR("star"),
    CAVE("cave"),
    PORTAL("portal"),
    FARM("farm"),
    SHOP("shop"),
    DANGER("danger");

    public static final Codec<MarkIcon> CODEC = StringRepresentable.fromEnum(MarkIcon::values);

    private final String name;

    MarkIcon(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
