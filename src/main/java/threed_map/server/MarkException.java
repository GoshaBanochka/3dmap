package threed_map.server;

import net.minecraft.network.chat.Component;

/** A rejected mark operation; the message is shown to the player. */
public final class MarkException extends Exception {
    private final Component component;

    public MarkException(Component component) {
        super(component.getString(), null, false, false);
        this.component = component;
    }

    public Component component() {
        return component;
    }
}
