package threed_map.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** All marks of the server, stored in the overworld's data folder (data/threed_map_marks.dat). */
public final class MarkStorage extends SavedData {
    static final String FILE_ID = "threed_map_marks";
    static final Factory<MarkStorage> FACTORY = new Factory<>(MarkStorage::new, MarkStorage::load, null);

    private final Map<UUID, Mark> marks = new LinkedHashMap<>();

    public static MarkStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    private static MarkStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        MarkStorage storage = new MarkStorage();
        ListTag list = tag.getList("marks", Tag.TAG_COMPOUND);
        for (Tag entry : list) {
            Mark.CODEC.parse(NbtOps.INSTANCE, entry)
                    .resultOrPartial(err -> ThreedMapMod.LOGGER.warn("Skipping broken mark: {}", err))
                    .ifPresent(m -> storage.marks.put(m.id(), m));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Mark mark : marks.values()) {
            Mark.CODEC.encodeStart(NbtOps.INSTANCE, mark)
                    .resultOrPartial(err -> ThreedMapMod.LOGGER.error("Failed to save mark {}: {}", mark.id(), err))
                    .ifPresent(list::add);
        }
        tag.put("marks", list);
        return tag;
    }

    public Collection<Mark> all() {
        return Collections.unmodifiableCollection(marks.values());
    }

    public Mark get(UUID id) {
        return marks.get(id);
    }

    public void put(Mark mark) {
        marks.put(mark.id(), mark);
        setDirty();
    }

    public Mark remove(UUID id) {
        Mark removed = marks.remove(id);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public int countOwnedBy(UUID owner) {
        int count = 0;
        for (Mark mark : marks.values()) {
            if (mark.owner().equals(owner)) {
                count++;
            }
        }
        return count;
    }
}
