package threed_map.common.mark;

/** Default rules shared by server validation and client GUI. Stage 4 makes them configurable. */
public final class MarkLimits {
    public static final int MAX_MARKS_PER_PLAYER = 50;
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_DESCRIPTION_LENGTH = 256;
    public static final int MAX_DESCRIPTION_LINES = 8;

    private MarkLimits() {
    }
}
