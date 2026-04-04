package tacticalle.invtweaks;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.network.chat.Component;

/**
 * Classifies a container's AbstractContainerMenu into a category.
 * Used to gate clipboard operations — incompatible containers block copy/paste/cut.
 *
 * NOTE: All Yarn class names have been verified against the 1.21.11 mappings.
 * If any import fails to resolve, the offending class name needs to be updated.
 */
public class ContainerClassifier {

    public enum ContainerCategory {
        STANDARD,       // ChestMenu — chests, barrels, shulker boxes (27 or 54 slots)
        ENDER_CHEST,    // ChestMenu with ender chest title — separate clipboard tracking
        GRID9,          // DispenserMenu — dispensers, droppers
        CRAFTER,        // CrafterMenu — crafter (3x3 with lockable slots)
        CRAFTING_TABLE, // CraftingMenu — crafting table (9 input + 1 output)
        HOPPER,         // HopperMenu — hopper (5 slots)
        FURNACE,        // AbstractFurnaceScreenHandler subtypes — furnaces
        INCOMPATIBLE,   // Everything else — villager trading, anvil, enchanting, etc.
        PLAYER_ONLY     // No container open — just the player inventory (E key)
    }

    /**
     * Classify a AbstractContainerMenu into a ContainerCategory (without title — no ender chest detection).
     * PLAYER_ONLY is detected separately (caller checks isPlayerOnly before calling this).
     */
    public static ContainerCategory classifyContainer(AbstractContainerMenu handler) {
        return classifyContainer(handler, null);
    }

    /**
     * Classify a AbstractContainerMenu into a ContainerCategory, using the screen title
     * to distinguish ender chests from regular chests.
     */
    public static ContainerCategory classifyContainer(AbstractContainerMenu handler, Component title) {
        if (handler == null) {
            return ContainerCategory.INCOMPATIBLE;
        }

        // Check in order of most common first
        if (handler instanceof ShulkerBoxMenu) {
            return ContainerCategory.STANDARD;
        }
        if (handler instanceof ChestMenu) {
            // Check for ender chest by screen title
            if (title != null) {
                String titleString = title.getString();
                String enderChestTitle = Component.translatable("container.enderchest").getString();
                if (titleString.equals(enderChestTitle)) {
                    return ContainerCategory.ENDER_CHEST;
                }
            }
            return ContainerCategory.STANDARD;
        }
        if (handler instanceof DispenserMenu) {
            return ContainerCategory.GRID9;
        }
        if (handler instanceof CrafterMenu) {
            return ContainerCategory.CRAFTER;
        }
        if (handler instanceof CraftingMenu) {
            return ContainerCategory.CRAFTING_TABLE;
        }
        if (handler instanceof HopperMenu) {
            return ContainerCategory.HOPPER;
        }
        // Check all furnace variants — they share AbstractFurnaceScreenHandler
        // but we check concrete types in case the abstract isn't accessible
        if (handler instanceof FurnaceMenu
                || handler instanceof BlastFurnaceMenu
                || handler instanceof SmokerMenu) {
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
            case ENDER_CHEST -> "ENDER_CHEST";
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
