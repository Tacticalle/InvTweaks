# InvTweaks

A client-side Fabric mod for Minecraft that gives you precise control over how many items you pick up, transfer, throw, and manage. Hold modifier keys while clicking to leave one behind, take exactly one, throw half a stack, fill existing stacks, transfer items by scrolling, and copy/paste container layouts.

## Features

- **All But 1** — Hold a modifier key while clicking to pick up or transfer a stack but leave one item behind
- **Only 1** — Hold a second modifier key to pick up or transfer exactly one item
- **Shift+Click Transfer** — Both modes work with shift-click quick-move between inventories and containers
- **Bundle Support** — Extract from and insert into bundles with the same modifier controls
- **Bundle Clipboard** — Copy a bundle's contents to the clipboard while hovering a bundle, then paste the layout into another bundle. Items are pulled from available inventory slots. Bundle entries appear in the clipboard history browser with dynamic grid previews.
- **Throw Half** — Hold the Misc modifier + Q to throw half a stack, both in-GUI and first-person
- **Throw All But 1** — Hold the AllBut1 modifier + Q to throw all but one item. Has its own independent enable/disable toggle.
- **Hotbar Modifiers** — Press a number key (1-9) while holding a modifier to move all-but-1 or exactly-1 to that hotbar slot. Always enabled.
- **Fill Existing Stacks** — Hold the Misc modifier + Shift+Click to distribute items only into existing partial stacks. Also works with scroll (Misc + scroll) to top up partial stacks on the destination side without placing items in empty slots.
- **Scroll Transfer** — Scroll up to move matching items to the container, scroll down to move them to your inventory. Supports four modes: bare scroll (flush), AllBut1+scroll (leave-1), Misc+scroll (fill-existing), AllBut1+Misc+scroll (leave-1 + fill-existing).
- **Copy/Paste Layout** — Copy a container or inventory layout (including armor and offhand), paste to rearrange items to match, or cut to copy+empty. All keybinds are configurable in the Hotkeys tab. Paste fills each slot to max stack size using available items. Component data (enchantments, potion types, etc.) is tracked for accurate matching. Paste works even with items on your cursor. Partial paste places as many items as possible when room is limited.
- **Small Container Clipboard** — Copy and paste layouts for dispensers, droppers, crafters, crafting tables (grid9), hoppers (hopper5), and furnaces/blast furnaces/smokers (furnace2). Each type has its own clipboard category and active index. Crafting tables exclude the output slot; furnaces exclude the output slot and copy only input + fuel.
- **Crafter Lock Support** — Copying a crafter captures which slots are disabled. Pasting into a crafter toggles slots to match the clipboard's lock state before placing items. Pasting a crafter clipboard into a non-crafter skips locked entries.
- **Even Distribution** — Small container pastes (grid9, hopper5, furnace2) distribute items evenly across target slots instead of maximizing individual stacks. Standard containers (chests) continue to maximize.
- **Container Classification** — Containers are automatically classified by type. Incompatible containers (villager trading, anvil, enchanting table, grindstone, loom, cartography table, beacon, brewing stand, stonecutter, smithing table) block copy/paste/cut operations with an "Incompatible" message.
- **Half-Selector for Size Mismatches** — Pasting a double chest layout into a single chest shows an interactive overlay to choose the Top Half or Bottom Half (click or press A/D). Pasting a single chest layout into a double chest supports three modes (configurable): hover position, menu selection, or arrow keys.
- **Clipboard History** — Maintains a history of past clipboard snapshots. Press the clipboard history key (configurable) while an inventory is open to browse, select, or delete saved layouts with item preview grids and vanilla-style hover tooltips. Supports multi-select deletion (Shift+click for range, Ctrl/Cmd+click to toggle). Duplicate copies automatically replace older identical entries. Small container entries display with type-appropriate grid layouts (3x3, 5x1, 2x1).
- **Clipboard Favorites** — Star any clipboard history entry to mark it as a favorite. Favorited entries are never evicted by the max history limit, can't be individually deleted, and are skipped by "Clear All." A "Favorites" tab in the clipboard history browser shows only favorited entries. Maximum 50 favorites.
- **Paste/Cut Undo** — Press the undo key (default: Ctrl+Z, configurable) after pasting or cutting to restore the pre-operation state. Single-level undo: each new paste/cut overwrites the previous snapshot. In-memory only, cleared when the inventory screen is closed. Works across all supported container types.
- **Death Auto-Snapshot** — Player inventory (including armor and offhand) is automatically saved to clipboard history on death so you can paste your layout back after respawning
- **Persistent Clipboard** — Clipboard history is saved to disk and survives game restarts
- **Message Overlay** — Feedback messages render next to the GUI instead of in chat. Can be toggled off via the `showOverlayMessages` config option.
- **Config Screen Tooltips** — Every entry in the config screen has a hover tooltip explaining what it does.
- **Configurable Keys** — Rebind all modifier keys to whatever you prefer. Three global modifier keys (AllBut1, Only1, Misc) with OS-specific defaults. Bundle-specific key overrides available.
- **Per-Feature Toggles** — Enable or disable each feature individually
- **Per-Tweak Key Overrides** — Set custom modifier keys for individual tweaks via the collapsible Advanced Options section in the Hotkeys tab

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.x
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `invtweaks-x.x.x.jar` into your `mods/` folder
4. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) for an in-game config screen

## Configuration

If you have Mod Menu installed, click the config button next to InvTweaks in the mod list. Otherwise, edit `config/invtweaks.json` directly. You can also press K (configurable) in-game to open the config screen directly.

The config screen has four tabs: All (simplified overview), Tweaks (feature toggles and settings), Hotkeys (keybinds, bundle keys, and collapsible per-tweak overrides), and Debug (logging toggle, copy debug log, report bug).

### Three Global Modifier Keys

InvTweaks uses three global modifier keys with OS-specific defaults:

| Modifier | Mac Default | Windows/Linux Default | Used By |
|----------|-------------|----------------------|---------|
| **AllBut1** | Left Cmd (Super) | Left Alt | All-but-1 tweaks, throw all-but-1, scroll leave-1 |
| **Only1** | Left Ctrl | Left Ctrl | Only-1 tweaks |
| **Misc** | Left Option (Alt) | Right Alt | Throw half, fill existing stacks |

Single-key tweaks (throw half, throw all-but-1, fill existing, scroll leave-1) inherit from their parent global key by default. You can override any key individually.

### Default Keybinds

| Key | Action |
|-----|--------|
| AllBut1 + Click | Pick up all but 1 |
| Only1 + Click | Pick up exactly 1 |
| AllBut1 + Shift+Click | Transfer all but 1 |
| Only1 + Shift+Click | Transfer exactly 1 |
| Misc + Q | Throw half stack |
| AllBut1 + Q (on a slot) | Throw all but 1 |
| Misc + Shift+Click | Fill existing stacks only |
| Scroll Up/Down | Transfer all matching items |
| AllBut1 + Scroll | Transfer matching items, leave 1 |
| Misc + Scroll | Fill existing partial stacks only |
| AllBut1 + Misc + Scroll | Leave 1 + fill existing only |
| `Ctrl`+C / `Ctrl`+V / `Ctrl`+X | Copy / Paste / Cut layout (configurable) |
| `Ctrl`+Z | Undo last paste/cut (configurable) |
| `Shift`+`Tab` | Open clipboard history browser (configurable) |
| `K` | Open config screen (configurable) |

Both left and right variants of modifier keys are recognized.

## Compatibility

InvTweaks is designed to work alongside other inventory mods:

- **[Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks)** — Fully compatible. InvTweaks detects Mouse Tweaks shift-drag operations and works correctly alongside them. Note: Mouse Tweaks' scroll wheel features may conflict with InvTweaks scroll transfer — disable one or the other if needed. The scroll leave-1 modifier key may also trigger allBut1 click pickup via phantom Mouse Tweaks events if both are bound to the same key; rebind one of them as a workaround.
- **[Mod Menu](https://modrinth.com/mod/modmenu)** — Provides an in-game configuration screen.
- **Other inventory mods** — InvTweaks uses mixin injection on `HandledScreen` and should be compatible with most mods that don't override the same click handling methods.

### macOS Note

InvTweaks includes specific handling for macOS `Cmd+Shift+Click` bulk-move behavior, preventing conflicts with the modifier key system. macOS users should install [macOS Input Fixes](https://modrinth.com/mod/macos-input-fixes) for full compatibility.

**Cmd+Q conflict:** On macOS, the AllBut1 modifier defaults to Left Cmd (Super). This means Throw All-But-1 (Cmd+Q) triggers the macOS "Quit" shortcut, which will close the game. To fix this, go to System Settings → Keyboard → Keyboard Shortcuts → App Shortcuts and add a custom shortcut for "Quit java" with a different key combination. Alternatively, rebind the AllBut1 modifier key to something other than Cmd.

### Known Limitations

- **Creative Mode** — InvTweaks does not currently work in the player's inventory while in Creative mode. Creative inventory uses a different screen handler that bypasses the standard slot interaction system. Scroll transfer in Creative inventory is also disabled to avoid conflicting with Creative tab scrolling.
- **Incompatible Containers** — Copy, paste, and cut are blocked in containers where layout operations don't apply: villager trading, anvil, enchanting table, grindstone, loom, cartography table, beacon, brewing stand, stonecutter, and smithing table. The clipboard history browser still opens normally in these containers.
- **Bundle Paste Undo** — Undo is not supported after bundle paste operations. Ctrl+Z after a bundle paste shows "Nothing to undo."
- **Animal Chests** — Mule, donkey, and llama chests are not yet supported for clipboard operations.

## Building from Source

```bash
git clone https://github.com/Tacticalle/InvTweaks.git
cd InvTweaks
./gradlew build
```

The built jar will be in `build/libs/`.

## License

> **License:** As of v1.6.0, InvTweaks is released under a custom source-available license. See [LICENSE.md](LICENSE.md) for full terms. Versions prior to 1.6.0 remain under the MIT License.
