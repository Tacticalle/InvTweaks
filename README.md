# InvTweaks

**Better client-side inventory management for Minecraft (Fabric)**

I made this mod because I was tired of fiddling around in containers all the time. It takes care of the most frustrating parts of the game (to me at least), and adds a few new quality of life features.

---

## What Does It Do?

InvTweaks is a client-side Fabric mod that gives you modifier-key controls over inventory actions. Hold a key, click a stack, and move all-but-1 or exactly-1 items. That core idea extends into shift-clicking, throwing, bundles, and scroll wheel transfers. I've also included a full clipboard system for copying and pasting inventory layouts, with a clipboard manager built in.

It's client-side only and works on vanilla servers.

One thing worth noting: since InvTweaks moves items faster than humanly possible, if your server monitors inventory transfer speed, you may want to give your admins a heads up so they don't think you're using a cheat client.

---

## Getting Started

InvTweaks works out of the box with sensible defaults. Here's where you can start:

1. **Open your inventory** and hover over a stack of items
   - **Hold Left Alt + click:** pick up all but one item
   - **Hold Left Ctrl + click:** pick up exactly one item
2. **Open a chest** and try **Alt + shift-click** on a stack. All but one transfers to the other side
3. **Scroll up or down** over an item in a chest to transfer all matching items
4. **Press Ctrl+C** in a chest to copy its layout. Take those items out and press **Ctrl+V** to restore it
5. **Press K** to open the config screen and explore

**Default modifier keys:**

| Windows / Linux | Mac         | Role                                          |
| :-------------- | :---------- | :-------------------------------------------- |
| Left Alt        | Left Cmd    | AllBut1 — move all except one                 |
| Left Ctrl       | Left Ctrl   | Only1 — move exactly one                      |
| Right Alt       | Left Option | Misc — throw half, fill existing, scroll fill |

All keys are fully rebindable in the config screen (K).

---
## Features

### Modifier-Key Tweaks

InvTweaks works primarily with keybind shortcuts. No GUI buttons or things that need constant clicking. Hold a modifier key during an action to change how items get moved or picked up. 

**Click Pickup:** Hold AllBut1 (Left Alt / Cmd on Mac) + click a stack to pick up everything except one. Hold Only1 (Left Ctrl) + click to pick up exactly one.

**Shift+Click Transfer:** Hold AllBut1 + shift-click to transfer all-but-1 to the other side. Hold Only1 + shift-click to transfer exactly one.

### Bundle Operations

**Bundle Extract:** Right-click a bundle to pull items out: AllBut1 extracts all but one (one stays in the bundle), Only1 extracts exactly one.

**Bundle Insert:** Left-click with items onto a bundle (or with a bundle onto items): AllBut1 inserts all but one, Only1 inserts exactly one. Works in both directions: items-onto-bundle and bundle-onto-items.

### Throwing Tweaks

**Throw Half:** Hold the Misc key (Right Alt / Option on Mac) + Q to drop half a stack to that friend who always needs some of your food.

**Throw All-But-1:** Hold AllBut1 + Q to drop all but one item.

Both work in inventories and in first-person (no GUI open).

### Scroll Wheel Transfer

Scroll up or down over an item in a container to transfer all matching items to the other side. Four modes depending on which modifier keys you hold:

- **Bare scroll:** Moves everything.
- **AllBut1 + scroll:** Leave-1 mode. Leaves one of each stack behind.
- **Misc + scroll:** Fill-existing mode. Only tops up partial stacks on the destination side and won't fill empty slots.
- **AllBut1 + Misc + scroll:** Leave-1 + fill-existing. I have no idea why you would need this, but maybe you do!

### Fill Existing Stacks

Hold Misc + shift-click to distribute a stack across existing partial stacks of the same item on the other side. 

### Copy/Paste/Cut

This is the big one. I've always felt the Minecraft inventory system would benefit from being able to copy and paste, so I added it.

**Copy (Ctrl+C):** Snapshots your current inventory or container layout. Remembers every item, its slot position, enchantments, potion effects, custom names, the works.

**Paste (Ctrl+V):** Rearranges items to match a previously copied and selected layout. Items get matched by type and components, moved to their correct slots, and stacks get topped up to max.

**Cut (Ctrl+X):** Copies the layout, then empties the container into your inventory (or at least what can fit).

**Undo (Ctrl+Z):** Make a paste or cut you didn't mean to? Undo restores the previous state.

### Clipboard System

What you copy goes into a persistent clipboard history that survives after you quit the game.

**Clipboard History Browser (Shift+Tab):** Full-screen browser showing all your saved layouts with item previews. 

**Favorites:** Star any clipboard entry to favorite it. Favorites are protected from deletion, never get removed when the history fills up, and have their own tab in the browser. Use them for layouts you paste frequently like your main storage chest setup, your go-to inventory loadout, whatever.

**Death Snapshots:** When you die, your inventory is automatically saved to the clipboard. Respawn, find and pick up your death pile, and you can paste your old layout back.

### Container Support

Containers are classified into categories and handled appropriately:

**Standard containers** (chests, double chests, shulker boxes, ender chests, barrels)
	Full copy/paste. Shulker boxes and ender chests get their own clipboard categories so your ender chest layout doesn't overwrite your double chest layout.

**Small containers** (Dispensers, droppers, crafting tables, crafters, hoppers, furnaces, blast furnaces, and smokers)
	Each have their own clipboard type. Paste uses even distribution instead of stack maximization. For example, if you paste 30 cobblestone into a dispenser, each slot gets roughly the same amount. 
	Crafters are special: lock states are preserved. Copy a crafter with locked slots, paste it somewhere else, and the locked slots get re-locked automatically.

**Size-mismatched paste**: Pasting a single-chest layout into a double chest (or vice versa)? There are three configurable modes:
- **Hover Position:** For small-to-large pastes. Hover over the top or bottom half of the container to paste to that side.
- **Menu Selection:** A menu pops up to select which half you want.
- **Arrow Keys:** Use arrow keys to select which side things are pasted into.

**Incompatible containers:** Villager trading, anvils, enchanting tables, grindstones, looms, cartography tables, beacons, brewing stands, stonecutters, and smithing tables can't be copied/pasted.

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/)
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download InvTweaks from [Modrinth](https://modrinth.com/mod/invtweaks)
4. Drop the `.jar` into your `mods` folder
5. Launch the game

---

## Configuration

Press **K** to open the config screen.

The config screen has four tabs:

**All:** *Almost* every setting in one scrollable list.

**Tweaks:** Toggle individual features on or off.

**Hotkeys:** Set your global modifier keys, tweak-specific key overrides, and clipboard keybinds. There's an "Advanced Options" section for per-tweak key overrides if you want a specific tweak to have its own keybinds.

**Debug:** Toggle debug logging and access the debug log buffer. If something seems wrong, enable debug logging, reproduce the issue, and hit "Copy Debug Log" to grab the last 200 log entries. Useful for bug reports.

### Config File

Settings are stored in `.minecraft/config/invtweaks.json`. Clipboard history is stored separately in `invtweaks-clipboard.json`.

---

## Mod Compatibility

I use a lot of inventory quality-of-life mods myself, so I've tried to make InvTweaks play nice with as many as possible. As far as my testing has shown, there are no major issues, but there are a few things to be aware of. If you run into compatibility problems, please [open an issue](https://github.com/Tacticalle/InvTweaks/issues) and I'll see what I can do.

### Mouse Tweaks

Generally compatible, with one note: Mouse Tweaks uses scroll for its own inventory operations. If both mods have their scroll features enabled, scroll behavior will conflict. You may need to disable one or the other, depending on which you prefer.

### Tweakeroo

Tweakeroo has its own scroll wheel features that may conflict with InvTweaks scroll transfer. Same deal, you may need to disable one or the other.

### Mod Menu

Fully compatible.

### Creative Mode

InvTweaks does not have full compatibility in Creative mode. Creative inventory uses a completely different screen handler that bypasses the standard slot interaction system.

---

## A Note for macOS Users

macOS users should install [macOS Input Fixes](https://modrinth.com/mod/macos-input-fixes) for best compatibility. InvTweaks has built-in handling for macOS Cmd+Shift+Click bulk-move behavior and responds to both Ctrl and Cmd for copy/paste/cut operations.

I also recommend creating a custom keyboard shortcut to avoid quitting Minecraft unexpectedly. Cmd+Q will quit the application, which can happen if you have Cmd set as a modifier for throw operations. To fix this:

1. Go to **System Settings → Keyboard → Keyboard Shortcuts → App Shortcuts**
2. Add a new shortcut named exactly **"Quit java"**
3. Set the shortcut to something you won't accidentally press

This overrides the standard macOS quit shortcut for Minecraft specifically.


---

## FAQ / Troubleshooting

**Q: I installed the mod but nothing happens when I hold modifier keys.**
A: Make sure you have Fabric API installed. Also check that you don't have another mod overriding the same key events.

**Q: Scroll transfer doesn't work.**
A: If you have another mod with scroll operations installed, its scroll features may be intercepting events first. Try disabling scroll features in one mod or the other.

**Q: My paste put items in the wrong slots.**
A: Paste matches items by type and component data (enchantments, potions, custom names, etc.). If you don't have the exact same items available, paste does its best with what's there.

**Q: The overlay messages are annoying.**
A: You can turn them off! Open the config screen (K) → Tweaks tab → "Show Overlay Messages" → disable.

**Q: Can I use this on a server?**
A: Yes. InvTweaks is entirely client-side and works on vanilla servers, Fabric servers, and any server that allows client-side mods. No server-side installation needed. If your server has strict anti-cheat monitoring, consider letting your admins know you're using an inventory management mod.

**Q: Something broke. How do I report a bug?**
A: Enable debug logging in the config screen (Debug tab), reproduce the issue, then click "Copy Debug Log" to grab the recent log entries. Open an issue on [GitHub](https://github.com/Tacticalle/InvTweaks/issues) with the debug log and a description of what happened. Screenshots help too.

**Q: Will this work on Minecraft version X?**
A: InvTweaks currently targets Minecraft 1.21.11 on Fabric. Other versions may be supported in the future. Check [Modrinth](https://modrinth.com/mod/invtweaks) for the latest supported versions.

---
## A note on the name

> You may recognize the mod name InvTweaks: There was a mod by this same name for older Minecraft versions (last official update around 1.10ish, with community updates through 1.20.) This mod is **not** those or related in any way. This is a completely different, independent mod that happens to share the name. 

---

## License

InvTweaks is released under a [custom source-available license](https://github.com/Tacticalle/InvTweaks/blob/main/LICENSE.md). Source code is available on GitHub for transparency and community trust, but redistribution, modification, and derivative works are restricted. See the LICENSE.md file for full terms.

Versions prior to v1.6.0 were released under the MIT License.
