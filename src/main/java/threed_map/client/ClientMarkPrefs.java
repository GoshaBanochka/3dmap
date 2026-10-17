package threed_map.client;

import threed_map.ThreedMapMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Personal display preferences that never go to the server: which marks this player hid on their map.
 * Stored per server next to the map cache (hidden_marks.txt, one id per line). Main thread only.
 */
public final class ClientMarkPrefs {
    private static final Set<UUID> HIDDEN = new HashSet<>();
    private static Path file;

    private ClientMarkPrefs() {
    }

    /** Loads the preferences of a server's cache folder; null unloads. */
    public static void open(Path worldDir) {
        HIDDEN.clear();
        file = worldDir == null ? null : worldDir.resolve("hidden_marks.txt");
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                try {
                    HIDDEN.add(UUID.fromString(line.strip()));
                } catch (IllegalArgumentException ignored) {
                    // skip garbage lines
                }
            }
        } catch (IOException e) {
            ThreedMapMod.LOGGER.warn("Could not read {}: {}", file, e.toString());
        }
    }

    public static boolean isHidden(UUID mark) {
        return HIDDEN.contains(mark);
    }

    public static void toggleHidden(UUID mark) {
        if (!HIDDEN.remove(mark)) {
            HIDDEN.add(mark);
        }
        save();
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, HIDDEN.stream().map(UUID::toString).collect(Collectors.joining("\n")));
        } catch (IOException e) {
            ThreedMapMod.LOGGER.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
