package tacticalle.invtweaks;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles saving/loading clipboard history to disk as JSON.
 */
public class ClipboardStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("invtweaks");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORAGE_PATH = FabricLoader.getInstance().getConfigDir().resolve("invtweaks-clipboard.json");
    private static final int FORMAT_VERSION = 1;

    /**
     * Save the current clipboard history to disk.
     */
    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("activeContainerIndex", LayoutClipboard.getActiveContainerIndex());
            root.addProperty("activePlayerIndex", LayoutClipboard.getActivePlayerIndex());
            root.addProperty("activeBundleIndex", LayoutClipboard.getActiveBundleIndex());

            JsonArray historyArray = new JsonArray();
            for (LayoutClipboard.HistoryEntry entry : LayoutClipboard.getHistory()) {
                JsonObject entryObj = new JsonObject();
                entryObj.addProperty("label", entry.label);
                entryObj.addProperty("timestamp", entry.timestamp);
                entryObj.addProperty("playtimeMinutes", entry.playtimeMinutes);
                entryObj.addProperty("isPlayerInventory", entry.snapshot.isPlayerInventory);
                entryObj.addProperty("slotCount", entry.snapshot.slotCount);
                if (entry.containerTitle != null) {
                    entryObj.addProperty("containerTitle", entry.containerTitle);
                }
                if (entry.entryType != null) {
                    entryObj.addProperty("entryType", entry.entryType);
                }

                JsonObject slotsObj = new JsonObject();
                for (Map.Entry<Integer, LayoutClipboard.SlotData> slotEntry : entry.snapshot.slots.entrySet()) {
                    LayoutClipboard.SlotData sd = slotEntry.getValue();
                    if (sd.item() == null) {
                        slotsObj.add(String.valueOf(slotEntry.getKey()), JsonNull.INSTANCE);
                    } else {
                        JsonObject slotObj = new JsonObject();
                        slotObj.addProperty("item", Registries.ITEM.getId(sd.item()).toString());
                        slotObj.addProperty("count", sd.count());
                        if (sd.components() != null) {
                            slotObj.addProperty("components", sd.components());
                        }
                        slotsObj.add(String.valueOf(slotEntry.getKey()), slotObj);
                    }
                }
                entryObj.add("slots", slotsObj);
                historyArray.add(entryObj);
            }
            root.add("history", historyArray);

            Files.createDirectories(STORAGE_PATH.getParent());
            Files.writeString(STORAGE_PATH, GSON.toJson(root));
            InvTweaksConfig.debugLog("CLIPBOARD", "Saved %d history entries to disk", LayoutClipboard.getHistory().size());
        } catch (IOException e) {
            LOGGER.error("InvTweaks: Failed to save clipboard history", e);
        }
    }

    /**
     * Load clipboard history from disk.
     */
    public static void load() {
        if (!Files.exists(STORAGE_PATH)) {
            InvTweaksConfig.debugLog("CLIPBOARD", "No clipboard history file found, starting fresh");
            return;
        }

        try {
            String json = Files.readString(STORAGE_PATH);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != FORMAT_VERSION) {
                LOGGER.warn("InvTweaks: Clipboard history version mismatch (expected {}, got {}), skipping load", FORMAT_VERSION, version);
                return;
            }

            int activeContainerIndex = root.has("activeContainerIndex") ? root.get("activeContainerIndex").getAsInt() : -1;
            int activePlayerIndex = root.has("activePlayerIndex") ? root.get("activePlayerIndex").getAsInt() : -1;
            int activeBundleIndex = root.has("activeBundleIndex") ? root.get("activeBundleIndex").getAsInt() : 0;

            List<LayoutClipboard.HistoryEntry> entries = new ArrayList<>();
            JsonArray historyArray = root.getAsJsonArray("history");
            if (historyArray != null) {
                for (JsonElement elem : historyArray) {
                    JsonObject entryObj = elem.getAsJsonObject();
                    String label = entryObj.get("label").getAsString();
                    long timestamp = entryObj.get("timestamp").getAsLong();
                    long playtimeMinutes = entryObj.get("playtimeMinutes").getAsLong();
                    boolean isPlayerInventory = entryObj.get("isPlayerInventory").getAsBoolean();
                    int slotCount = entryObj.get("slotCount").getAsInt();

                    Map<Integer, LayoutClipboard.SlotData> slots = new LinkedHashMap<>();
                    JsonObject slotsObj = entryObj.getAsJsonObject("slots");
                    if (slotsObj != null) {
                        for (Map.Entry<String, JsonElement> slotEntry : slotsObj.entrySet()) {
                            int slotIndex = Integer.parseInt(slotEntry.getKey());
                            JsonElement slotElem = slotEntry.getValue();
                            if (slotElem.isJsonNull()) {
                                slots.put(slotIndex, new LayoutClipboard.SlotData(null, 0, null));
                            } else {
                                JsonObject slotObj = slotElem.getAsJsonObject();
                                String itemId = slotObj.get("item").getAsString();
                                int count = slotObj.get("count").getAsInt();
                                Item item = Registries.ITEM.get(Identifier.of(itemId));
                                // If item doesn't exist (mod removed), use air
                                if (item == Items.AIR && !itemId.equals("minecraft:air")) {
                                    LOGGER.warn("InvTweaks: Unknown item '{}' in clipboard history, skipping slot", itemId);
                                    slots.put(slotIndex, new LayoutClipboard.SlotData(null, 0, null));
                                } else {
                                    String components = slotObj.has("components") ? slotObj.get("components").getAsString() : null;
                                    slots.put(slotIndex, new LayoutClipboard.SlotData(item, count, components));
                                }
                            }
                        }
                    }

                    String containerTitle = entryObj.has("containerTitle") ? entryObj.get("containerTitle").getAsString() : null;
                    String entryType = entryObj.has("entryType") ? entryObj.get("entryType").getAsString() : null;
                    // Backwards compat: derive entryType from isPlayerInventory if not present
                    if (entryType == null) {
                        entryType = isPlayerInventory ? LayoutClipboard.TYPE_PLAYER : LayoutClipboard.TYPE_CONTAINER;
                    }

                    LayoutClipboard.LayoutSnapshot snapshot = new LayoutClipboard.LayoutSnapshot(slotCount, slots, isPlayerInventory);
                    entries.add(new LayoutClipboard.HistoryEntry(snapshot, label, timestamp, playtimeMinutes, containerTitle, entryType));
                }
            }

            LayoutClipboard.loadFromStorage(entries, activeContainerIndex, activePlayerIndex, activeBundleIndex);
            LOGGER.info("InvTweaks: Loaded {} clipboard history entries from disk", entries.size());
        } catch (Exception e) {
            LOGGER.error("InvTweaks: Failed to load clipboard history", e);
        }
    }
}
