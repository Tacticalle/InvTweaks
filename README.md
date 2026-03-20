# InvTweaks

A Fabric mod for Minecraft that gives you precise control over how many items you pick up, transfer, throw, and manage. Hold modifier keys while clicking to leave one behind, take exactly one, throw half a stack, fill existing stacks, transfer items by scrolling, and copy/paste container layouts.

## Features

- **All But 1** — Hold a modifier key (default: `Ctrl`) while clicking to pick up or transfer a stack but leave one item behind
- **Only 1** — Hold a second modifier key (default: `Alt`) to pick up or transfer exactly one item
- **Shift+Click Transfer** — Both modes work with shift-click quick-move between inventories and containers
- **Bundle Support** — Extract from and insert into bundles with the same modifier controls
- **Throw Half** — Hold a modifier key (default: `Alt`) + Q to throw half a stack, both in-GUI and first-person
- **Throw All But 1** — Hold a modifier key (default: `Ctrl`) + Q to throw all but one item
- **Hotbar Modifiers** — Press a number key (1–9) while holding a modifier to move all-but-1 or exactly-1 to that hotbar slot
- **Fill Existing Stacks** — Hold a modifier key (default: `Alt`) + Shift+Click to distribute items only into existing partial stacks
- **Scroll Transfer** — Scroll up to move matching items to the container, scroll down to move them to your inventory
- **Scroll Leave-1** — Hold a modifier key (default: `Ctrl`) while scrolling to leave one behind in each slot
- **Copy/Paste Layout** — Ctrl+C to snapshot a container or inventory layout (including armor and offhand), Ctrl+V to rearrange items to match, Ctrl+X to cut. Paste fills each slot to max stack size using available items. Component data (enchantments, potion types, etc.) is tracked for accurate matching.
- **Clipboard History** — Maintains a history of past clipboard snapshots. Press Shift+Tab while an inventory is open to browse, select, or delete saved layouts with item preview grids and hover tooltips.
- **Death Auto-Snapshot** — Player inventory (including armor and offhand) is automatically saved to clipboard history on death so you can paste your layout back after respawning
- **Persistent Clipboard** — Clipboard history is saved to disk and survives game restarts
- **Message Overlay** — Feedback messages render next to the GUI instead of in chat
- **Configurable Keys** — Rebind all modifier keys to whatever you prefer
- **Per-Feature Toggles** — Enable or disable each feature individually
- **Per-Tweak Key Overrides** — Set custom modifier keys for individual tweaks

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.x
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `invtweaks-x.x.x.jar` into your `mods/` folder
4. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) for an in-game config screen

## Configuration

If you have Mod Menu installed, click the config button next to InvTweaks in the mod list. Otherwise, edit `config/invtweaks.json` directly. You can also press K in-game to open the config screen directly.

The config screen has four tabs: All (everything), Tweaks (feature toggles), Hotkeys (keybinds and per-tweak overrides), and Debug (logging toggle).

### Default Keybinds

| Key | Action |
|-----|--------|
| `Ctrl` + Click | Pick up all but 1 |
| `Alt` + Click | Pick up exactly 1 |
| `Ctrl` + Shift+Click | Transfer all but 1 |
| `Alt` + Shift+Click | Transfer exactly 1 |
| `Alt` + Q | Throw half stack |
| `Ctrl` + Q (on a slot) | Throw all but 1 |
| `Alt` + Shift+Click | Fill existing stacks only |
| Scroll Up/Down | Transfer all matching items |
| `Ctrl` + Scroll | Transfer matching items, leave 1 |
| `Ctrl`+C / `Ctrl`+V / `Ctrl`+X | Copy / Paste / Cut layout |
| `Shift`+`Tab` | Open clipboard history browser |
| `K` | Open config screen |

Both left and right variants of modifier keys are recognized.

## Compatibility

InvTweaks is designed to work alongside other inventory mods:

- **[Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks)** — Fully compatible. InvTweaks detects Mouse Tweaks shift-drag operations and works correctly alongside them. Note: Mouse Tweaks' scroll wheel features may conflict with InvTweaks scroll transfer — disable one or the other if needed. Ctrl+scroll leave-1 mode may also trigger allBut1 click pickup via phantom Mouse Tweaks events; rebind `scrollLeave1Key` to a different key as a workaround.
- **[Mod Menu](https://modrinth.com/mod/modmenu)** — Provides an in-game configuration screen.
- **Other inventory mods** — InvTweaks uses mixin injection on `HandledScreen` and should be compatible with most mods that don't override the same click handling methods.

### macOS Note

InvTweaks includes specific handling for macOS `Cmd+Shift+Click` bulk-move behavior, preventing conflicts with the modifier key system. MacOS users should install [MacOS Input Fixes](https://modrinth.com/mod/macos-input-fixes) for full compatibility.

### Known Limitations

- **Creative Mode** — InvTweaks does not currently work in the player's inventory while in Creative mode. Creative inventory uses a different screen handler that bypasses the standard slot interaction system. Scroll transfer in Creative inventory is also disabled to avoid conflicting with Creative tab scrolling.
- **Size-Mismatched Paste** — Pasting a layout between different-sized containers (e.g., 27-slot chest into 54-slot double chest) is currently blocked. A proper size-mismatch GUI selector is planned for a future update.

## Credits

This mod is based on [All-But-1](https://modrinth.com/mod/all-but-1) by **Haage**, originally released under the [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/) public domain dedication.

**InvTweaks** by **Tacticalle** is a continuation and expansion of that work, including additional features, ongoing maintenance, and new additions. This version is licensed under the [MIT License](LICENSE).

## Building from Source

```bash
git clone https://github.com/Tacticalle/InvTweaks.git
cd InvTweaks
./gradlew build
```

The built jar will be in `build/libs/`.

## License

[MIT License](LICENSE)
