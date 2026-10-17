package threed_map.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Caps mark requests from the GUI per player. Every accepted edit is broadcast to all players,
 * so a spamming client must not be able to load the server. Server thread only.
 */
final class MarkRateLimiter {
    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MS = 5_000;
    private static final Map<UUID, Deque<Long>> REQUESTS = new HashMap<>();

    private MarkRateLimiter() {
    }

    static boolean tryAcquire(UUID player) {
        long now = System.currentTimeMillis();
        Deque<Long> times = REQUESTS.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() >= MAX_REQUESTS) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    static void forget(UUID player) {
        REQUESTS.remove(player);
    }
}
