package threed_map.common.mark;

import net.minecraft.util.RandomSource;

/** Fixed palette offered for marks. Colors are 0xRRGGBB. */
public final class MarkColors {
    public static final int[] PALETTE = {
            0xE53935, 0xFB8C00, 0xFDD835, 0x7CB342,
            0x00ACC1, 0x1E88E5, 0x8E24AA, 0xEC407A,
            0x6D4C41, 0xFFFFFF, 0x9E9E9E, 0x212121
    };

    private MarkColors() {
    }

    public static int random(RandomSource random) {
        return PALETTE[random.nextInt(PALETTE.length)];
    }

    /** Strips alpha; any 24-bit RGB value is acceptable. */
    public static int sanitize(int color) {
        return color & 0xFFFFFF;
    }
}
