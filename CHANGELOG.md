# Changelog

## [2.0.0] - 2026-03-30

### Modifier-Key Tweaks
- **All But 1** — Pick up or transfer an entire stack, leaving one item behind. Works with left-click pickup, shift+click transfer, hotbar buttons, and bundle extract/insert.
- **Only 1** — Pick up or transfer exactly one item. Works across the same interactions as All But 1.
- **Hotbar Button Modifiers** — Press number keys 1–9 with All But 1 or Only 1 held to move partial quantities to hotbar slots.
- **Bundle Extract/Insert** — Extract from or insert into bundles with modifier key control in both directions.

### Throwing Tweaks
- **Throw Half** — Hold the Misc modifier + Q to throw half a stack, both inside inventory screens and in first-person.
- **Throw All-But-1** — Hold the AllBut1 modifier + Q to throw all but one item. Independent enable/disable toggle from Throw Half.

### Fill Existing Stacks
- **Shift+Click Fill Existing** — Hold the Misc modifier + Shift+Click to distribute items only into existing partial stacks, never creating new ones.
- **Scroll Fill-Existing** — Hold the Misc modifier while scrolling to transfer items only into partial stacks on the destination side.
- **Leave-1 + Fill-Existing Combo** — Hold AllBut1 + Misc while scrolling to combine leave-1 and fill-existing behaviors.

### Scroll Wheel Transfer
- **Four Scroll Modes** — Bare scroll (flush transfer), AllBut1+scroll (leave-1), Misc+scroll (fill-existing), AllBut1+Misc+scroll (leave-1 + fill-existing).
- Scroll up moves all matching items to the container; scroll down moves to player inventory.
- Creative inventory scrolling is excluded to avoid tab conflicts.

### Copy / Paste / Cut System
- **Layout Copy** — Ctrl+C (or configured key) to snapshot the current container or player inventory layout, including full component data (enchantments, potions, custom names, dyes).
- **Layout Paste** — Ctrl+V to rearrange items to match a saved layout. Quantity maximization fills each slot to the item's max stack size when surplus is available. Two-pass algorithm handles partial pastes when inventory space is limited.
- **Layout Cut** — Ctrl+X to copy the layout and move all items to the other side. Player-only inventory shows "Layout copied" since there's no container to move to.
- **Cursor-Aware Paste** — Paste works even when the cursor holds items, using shift-click operations to avoid disturbing held items.
- **Single-Level Undo** — Press Ctrl+Z (or configured key) after a paste or cut to restore the pre-operation state. Works across all container types. Reports full or partial restoration via overlay.

### Clipboard System
- **Persistent Clipboard History** — Clipboard history saved to disk as JSON, survives game restarts. Configurable max entry count (default: 50).
- **Clipboard History Browser** — Full-screen GUI (Shift+Tab or configured key) showing all saved layouts with vanilla-style item previews, select/delete controls, and metadata.
- **Favorites** — Star any clipboard entry to protect it from eviction and deletion. Dedicated Favorites tab. Maximum 50 favorites.
- **Multi-Select Deletion** — Ctrl/Cmd+click toggle and Shift+click range select for bulk operations.
- **Death Auto-Snapshots** — Player inventory is automatically saved to clipboard on death, including armor and offhand.
- **Clipboard Deduplication** — Identical layout copies (same contents and container title) replace older entries instead of creating duplicates.
- **Configurable Keybinds** — Clipboard history, copy, paste, and cut keys are all individually rebindable.
- **Separate Active Indices** — Container, player inventory, and bundle clipboards each track their own selected entry independently.

### Bundle Clipboard
- **Bundle Copy** — Hover a bundle and press copy to snapshot its contents as a "bundle" type clipboard entry. Serializes full component data including nested bundles.
- **Bundle Paste** — Fills a bundle with items from inventory to match a saved bundle layout. Handles weight limits and missing items with descriptive messages.
- **Bundle Cut** — Copies the layout without extracting items; programmatic extraction is not supported.
- **Bundle Preview** — Bundle entries in the clipboard browser use a dark-themed preview grid matching vanilla bundle tooltip aesthetics. Dynamic column count scales with item count.

### Container Support
- **8-Category Container Classification** — Containers are classified on clipboard key press into: Standard, Grid9, Crafter, Crafting Table, Hopper, Furnace, Player-Only, and Incompatible.
- **Small Container Types** — Three dedicated clipboard types: grid9 (dispensers, droppers, crafters, crafting tables — 9 slots), hopper5 (hoppers — 5 slots), furnace2 (furnaces — 2 slots: input + fuel).
- **Even Distribution** — Small container pastes distribute items evenly across slots instead of maximizing individual stacks.
- **Crafter Lock-State Preservation** — Copying a crafter captures disabled slot state. Pasting toggles locks to match before placing items. Pasting a crafter clipboard into a non-crafter skips locked entries.
- **Size-Mismatched Paste** — Three modes for pasting between different container sizes: Hover Position (cursor Y relative to midpoint), Menu Selection (interactive overlay with preview grids), and Arrow Keys (hold Up/Down while pressing paste).
- **Incompatible Container Blocking** — Copy, paste, and cut are blocked in 10 incompatible container types (villager trading, anvil, enchanting table, etc.) with clear messaging. Clipboard history browser still opens.
- **Shulker Box Support** — Shulker boxes correctly classified as Standard containers.

### Config Screen
- **Four-Tab Layout** — All, Tweaks, Hotkeys, Debug tabs with responsive sizing (70% width, clamped 350–600px).
- **Three Global Modifier Keys** — AllBut1, Only1, and Misc modifier keys with OS-specific defaults (Mac: Cmd/Ctrl/Option; Windows/Linux: Alt/Ctrl/R.Alt).
- **Per-Tweak Key Overrides** — Individual tweaks can override global modifier keys. Collapsible Advanced Options section in Hotkeys tab.
- **Bundle Key Pair** — Dedicated bundle modifier key overrides with two-level inheritance (per-operation → bundle pair → global).
- **Single-Key Inheritance** — Throw, fill-existing, and scroll leave-1 keys default to inheriting from their parent global key.
- **Hover Tooltips** — Every config entry has a descriptive tooltip (300ms delay, auto-repositions near screen edges).
- **Editable Config Key** — The config screen open key (default: K) is editable in the Hotkeys tab.
- **Debug Ring Buffer** — 200-line circular buffer with a "Copy Debug Log" button. "Report Bug" button opens GitHub issues.
- **Scroll Position Preserved** — Config screen scroll position is maintained across within-tab rebuilds.

### Preview Rendering
- **Vanilla Slot Sprites** — Preview grids use vanilla Minecraft slot sprites via `drawGuiTexture()` for authentic appearance.
- **Matrix-Scaled Items** — Items, stack counts, durability bars, and enchantment glints all scale proportionally using matrix transforms.
- **Per-Type Layouts** — Player inventory previews match vanilla layout (armor column, offhand, 3×9 grid, hotbar gap). Furnace previews use vertical layout with fire icon. Bundle previews use dark theme.
- **Empty Armor/Offhand Sprites** — Empty armor and offhand slots render vanilla placeholder sprites that scale with slot size.
- **Crafter Locked Slot Overlay** — Disabled crafter slots render with the vanilla disabled-slot sprite.

### Bug Fixes
- Fixed paste bouncing on repeated Ctrl+V with cursor items (pass 2 now protects pass-1-filled slots)
- Fixed quantity maximization blocked when item types already match but stacks can be topped up
- Fixed config screen scroll position resetting on tab changes and option toggles
- Fixed fill-existing scroll placing items in empty slots (replaced QUICK_MOVE with manual click-merging)
- Fixed player inventory overlay not rendering in InventoryScreen (changed injection from `render` to `renderMain`)
- Fixed death snapshot armor mapping saving pieces in inverted order
- Fixed stale ItemStack reference during fill-existing scroll (source copied before pickup)
- Fixed undo executor failing due to sequential state mutation (replaced with pre-planned move algorithm)
- Fixed paste displacing non-armor items from slots 5–8 in containers
- Fixed overlay background stretching across screen at small GUI scales
- Fixed crafter lock toggle using wrong slot action type (changed to `clickButton`)
- Fixed even distribution counting errors in small container paste
- Fixed cut showing duplicate overlay messages on bundles
- Fixed cursor-held-item guard not triggering during paste
- Fixed "No matching items" and "Size mismatch" messages appearing simultaneously
- Fixed clipboard history preview extending past panel bounds
- Fixed `reconstructStack` log spam (cached failed component strings, warns once per unique failure)
- Fixed scroll leave-1 failing silently when source side is full (now searches destination for temp slots)
- Fixed All But 1 + Shift+Click creating 63-item stacks with partial destination stacks
- Fixed shulker boxes classified as Incompatible (added missing handler check)
- Fixed Ctrl+X in player-only inventory showing misleading "Layout cut" message

### Removed
- Key flip system (replaced by per-tweak key overrides)
- Clipboard expiry by playtime (max history count is the only limit)
- `enableHotbarModifiers` config field (hotbar modifiers are always enabled)
- Scroll-requires-modifier config option (bare scroll always triggers flush mode)

## [1.3.1] - 2026-03-20

### Bug Fixes
- **Component-Aware Clipboard** — Clipboard now tracks full item component data (enchantments, potion types, ominous bottle levels, tipped arrow variants, etc.) so paste correctly distinguishes between items of the same type with different data
- Fixed death snapshot armor mapping — armor pieces were being saved in inverted order (boots in helmet slot and vice versa) due to a mismatch between PlayerInventory indices and snapshot key ordering
- Fixed container paste displacing non-armor items from slots 5–8 — armor validation now only runs in player inventory, preventing shulker boxes, logs, and other items from being rejected out of regular container slots
- Improved paste matching for same-type items with different component data — new four-tier ranking system prefers exact component matches and prioritizes items not already in their target position

## [1.3.0] - 2026-03-20

### Features
- **Armor & Offhand in Player Inventory Copy/Paste** — Player inventory clipboard now captures armor slots (helmet, chestplate, leggings, boots) and offhand, for a total of 41 slots. Death auto-snapshots also include armor and offhand.
- **Paste Quantity Maximization** — Pasting a layout now fills each target slot to the item's max stack size rather than the exact count from the clipboard snapshot. If you copied 16 diamonds, pasting will fill the slot to 64 if enough diamonds are available.
- **Preview Grid Redesign** — Clipboard history preview grid now scales item icons to match dynamic slot sizes. Empty slots use distinct muted colors. Hovering an item in the preview shows its full tooltip. Player inventory preview shows a 5th row for armor (4 slots) and offhand (1 slot).
- **Preview Summary Format** — Clipboard history preview now shows "X/Y slots taken" instead of "X stacks, Y items"

### Bug Fixes
- Fixed paste bouncing on repeated Ctrl+V — the "already matches" check now compares item type only (not count), so pressing Ctrl+V again after a successful paste correctly shows "Layout already matches!" instead of reshuffling items
- Paste displacement now prefers non-hotbar slots when moving items out of the way, reducing unwanted hotbar clutter during paste operations

## [1.2.1] - 2026-03-20

### Bug Fixes
- Fixed "No matching items" and "Size mismatch" messages appearing simultaneously — "No matching items" now takes priority
- Blocked paste between different-sized containers (e.g., 27-slot into 54-slot) — shows "Layout size incompatible" instead of producing inconsistent results
- Ctrl+X in player-only inventory now shows "Layout copied" instead of "Layout cut" since there's no container to move items out of
- Fixed empty slot color in clipboard history preview grid — was blending with panel background, now uses visible medium gray
- Fixed clipboard history preview grid extending past panel bounds — grid now scales dynamically to fit within the panel
- Fixed cursor-held-item guard not triggering during paste — paste now aborts with "Put away held items first" when cursor is not empty

### Removed
- **Scroll Requires Modifier** — Removed config option. Bare scroll now always triggers flush mode when scroll transfer is enabled.

## [1.2.0] - 2026-03-19

### Features
- **Clipboard History** — Maintains a history ring of past clipboard snapshots, navigable via Shift+Tab while an inventory is open
- **Clipboard History Browser** — Full-screen GUI showing all saved layouts with item preview grids, select/delete controls, and metadata
- **Persistent Clipboard Storage** — Clipboard history is saved to disk as JSON and survives game restarts
- **Death Auto-Snapshot** — Player inventory is automatically saved to clipboard history on death
- **Clipboard Expiry** — Configurable playtime-based auto-deletion of old clipboard entries (default: never expire)
- **Clipboard Max History** — Configurable maximum number of clipboard entries to keep (default: 50)
- **Throw Half** — Hold modifier key (default: Alt) + Q to throw half a stack, both in-GUI and first-person
- **Throw All But 1** — Hold modifier key (default: Ctrl) + Q to throw all but one item
- **Hotbar Button Modifiers** — Press number keys 1–9 with All But 1 or Only 1 modifier to move partial quantities to hotbar slots
- **Fill Existing Stacks** — Hold modifier key (default: Alt) + Shift+Click to distribute items only into existing partial stacks, never creating new ones
- **Scroll Wheel Transfer** — Scroll up to move all matching items to the container, scroll down to move to player inventory
- **Scroll Leave-1 Mode** — Hold modifier key (default: Ctrl) while scrolling to leave one behind in each slot
- **Copy/Paste Layout** — Ctrl+C to snapshot container or player inventory layout, Ctrl+V to rearrange items to match, Ctrl+X to cut (copy + move all out)
- **Separate Clipboards** — Container and player inventory use independent clipboards to prevent cross-contamination
- **Message Overlay** — Feedback messages now render next to the GUI with fade animation instead of appearing in chat
- **Per-Tweak Modifier Key Overrides** — Set custom All But 1 / Only 1 keys for individual tweaks via Global/Custom toggle in Hotkeys tab

### Changes
- **Tabbed Config Screen** — Config GUI reorganized into four tabs: All, Tweaks, Hotkeys, Debug
- **Responsive Config Layout** — Config screen scales with window size (70% width, clamped 350–600px)
- **ESC Clears Keybinds** — Pressing ESC during key capture sets the key to NONE instead of canceling
- **Debug Logging** — Enhanced debug logging with `[IT:TAG]` format and per-feature tags throughout all code paths
- **Creative Scroll Hotfix** — Scroll transfer disabled in Creative inventory to avoid conflicting with Creative tab scrolling
- **Player inventory clipboard now includes hotbar** — Copy/paste captures all 36 player inventory slots (9–35 main + 0–8 hotbar)
- **Paste no-match detection** — Paste shows "No matching items available" when clipboard items aren't present in accessible slots
- **Paste already-matches detection** — Paste shows "Layout already matches!" when the container already matches the clipboard

### Removed
- **Key Flip** — Removed entirely. Per-tweak key overrides replace this functionality.

### Bug Fixes
- Fixed config keybind (K) not opening config screen — now registered with Fabric KeyBindingHelper
- Removed stale `leave_one` vanilla keybind that appeared in Controls menu
- Fixed All But 1 + Shift+Click creating 63-item stacks when destination had a partial stack
- Fixed All But 1 + Shift+Click regression where single items weren't transferring normally

## [1.0.0] - 2025-02-26

### Initial Release (as InvTweaks)

Forked from [All-But-1](https://modrinth.com/mod/all-but-1) by Haage and rebranded as InvTweaks by Tacticalle.

#### Features
- **All But 1** — Pick up or transfer an entire stack, leaving one item behind (default: Ctrl+Click)
- **Only 1** — Pick up or transfer exactly one item (default: Alt+Click)
- **Shift+Click Transfer** — Both modes work with shift-click quick-move
- **Bundle Extract** — Extract from bundles with modifier key control
- **Bundle Insert** — Insert into bundles with modifier key control (both directions)
- **Configurable Modifier Keys** — Rebind both keys to any key
- **Per-Feature Toggles** — Enable/disable each feature individually
- **Mod Menu Integration** — In-game config screen
- **Mouse Tweaks Compatibility** — Full compatibility with Mouse Tweaks shift-drag
- **macOS Support** — Handles macOS Cmd+Shift+Click bulk-move correctly
