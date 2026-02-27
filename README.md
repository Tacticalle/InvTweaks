# InvTweaks

A Fabric mod for Minecraft that gives you precise control over how many items you pick up, transfer, and insert. Hold modifier keys while clicking to leave one behind, take exactly one, or manage bundles with surgical precision.

## Features

- **All But 1** — Hold a modifier key (default: `Ctrl`) while clicking to pick up or transfer a stack but leave one item behind
- **Only 1** — Hold a second modifier key (default: `Alt`) to pick up or transfer exactly one item
- **Shift+Click Transfer** — Both modes work with shift-click quick-move between inventories and containers
- **Bundle Support** — Extract from and insert into bundles with the same modifier controls
- **Configurable Keys** — Rebind both modifier keys to whatever you prefer
- **Per-Feature Toggles** — Enable or disable each feature individually
- **Key Flip** — Swap what the two modifier keys do on a per-feature basis

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 1.21.x
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `invtweaks-x.x.x.jar` into your `mods/` folder
4. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) for an in-game config screen

## Configuration

If you have Mod Menu installed, click the config button next to InvTweaks in the mod list. Otherwise, edit `config/invtweaks.json` directly.

### Default Keybinds

| Key | Action |
|-----|--------|
| `Ctrl` + Click | Pick up all but 1 |
| `Alt` + Click | Pick up exactly 1 |
| `Ctrl` + Shift+Click | Transfer all but 1 |
| `Alt` + Shift+Click | Transfer exactly 1 |

Both left and right variants of modifier keys are recognized.

## Compatibility

InvTweaks is designed to work alongside other inventory mods:

- **[Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks)** — Fully compatible. InvTweaks detects Mouse Tweaks shift-drag operations and works correctly alongside them.
- **[Mod Menu](https://modrinth.com/mod/modmenu)** — Provides an in-game configuration screen.
- **Other inventory mods** — InvTweaks uses mixin injection on `HandledScreen` and should be compatible with most mods that don't override the same click handling methods.

### macOS Note

InvTweaks includes specific handling for macOS `Cmd+Shift+Click` bulk-move behavior, preventing conflicts with the modifier key system.

### Known Limitations

- **Creative Mode** — InvTweaks does not currently work in the player's inventory while in Creative mode. Creative inventory uses a different screen handler that bypasses the standard slot interaction system. This may be addressed in a future update.

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
