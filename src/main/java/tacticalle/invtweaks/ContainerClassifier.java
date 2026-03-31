package tacticalle.invtweaks;

import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.CrafterScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;

/**
 * Classifies a container's ScreenHandler into a category.
 * Used to gate clipboard operations — incompatible containers block copy/paste/cut.
 *
 * NOTE: All Yarn class names have been verified against the 1.21.11 mappings.
 * If any import fails to resolve, the offending class name needs to be updated.
 */
public class ContainerClassifier {

    public enum ContainerCategory {
        STANDARD,       // GenericContainerScreenHandler — chests, barrels, ender chests, shulker boxes (27 or 54 slots)
        GRID9,          // Generic3x3ContainerScreenHandler — dispensers, droppers
        CRAFTER,        // CrafterScreenHandler — crafter (3x3 with lockable slots)
        CRAFTING_TABLE, // CraftingScreenHandler — crafting table (9 input + 1 output)
        HOPPER,         // HopperScreenHandler — hopper (5 slots)
        FURNACE,        // AbstractFurnaceScreenHandler subtypes — furnaces
        INCOMPATIBLE,   // Everything else — villager trading, anvil, enchanting, etc.
        PLAYER_ONLY     // No container open — just the player inventory (E key)
    }

    /**
     * Classify a ScreenHandler into a ContainerCategory.
     * PLAYER_ONLY is detected separately (caller checks isPlayerOnly before calling this).
     */
    public static ContainerCategory classifyContainer(ScreenHandler handler) {
        if (handler == null) {
            return ContainerCategory.INCOMPATIBLE;
        }

        // Check in order of most common first
        if (handler instanceof ShulkerBoxScreenHandler) {
            return ContainerCategory.STANDARD;
        }
        if (handler instanceof GenericContainerScreenHandler) {
            return ContainerCategory.STANDARD;
        }
        if (handler instanceof Generic3x3ContainerScreenHandler) {
            return ContainerCategory.GRID9;
        }
        if (handler instanceof CrafterScreenHandler) {
            return ContainerCategory.CRAFTER;
        }
        if (handler instanceof CraftingScreenHandler) {
            return ContainerCategory.CRAFTING_TABLE;
        }
        if (handler instanceof HopperScreenHandler) {
            return ContainerCategory.HOPPER;
        }
        // Check all furnace variants — they share AbstractFurnaceScreenHandler
        // but we check concrete types in case the abstract isn't accessible
        if (handler instanceof FurnaceScreenHandler
                || handler instanceof BlastFurnaceScreenHandler
                || handler instanceof SmokerScreenHandler) {
            return ContainerCategory.FURNACE;
        }

        // Everything else: villager trading, anvil, enchanting table, grindstone,
        // loom, cartography table, beacon, brewing stand, stonecutter, smithing table
        return ContainerCategory.INCOMPATIBLE;
    }

    /**
     * Get a human-readable name for a container category.
     */
    public static String getCategoryName(ContainerCategory category) {
        return switch (category) {
            case STANDARD -> "STANDARD";
            case GRID9 -> "GRID9";
            case CRAFTER -> "CRAFTER";
            case CRAFTING_TABLE -> "CRAFTING_TABLE";
            case HOPPER -> "HOPPER";
            case FURNACE -> "FURNACE";
            case INCOMPATIBLE -> "INCOMPATIBLE";
            case PLAYER_ONLY -> "PLAYER_ONLY";
        };
    }
}
