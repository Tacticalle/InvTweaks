# Changelog

## [1.4.0] - 2026-03-20

### Features
- **Half-Selector Overlay** — When pasting a 54-slot (double chest) clipboard into a 27-slot (single chest) container, an interactive overlay appears letting you choose the Top Half or Bottom Half to paste. Uses keyboard shortcuts (A for top, D for bottom) or click the preview grids.
- **Small-to-Large Auto-Paste** — Pasting a 27-slot clipboard into a 54-slot container automatically pastes into the top rows without user interaction, showing "Layout pasted (top half)"
- **Cursor-Aware Paste** — Paste no longer requires an empty cursor. When items are on the cursor, paste uses shift-click (QUICK_MOVE) operations only to avoid disturbing the held items. Shows "Items on cursor held" alongside the paste result.
- **Partial Paste** — When paste cannot place all items due to lack of room, it places as many as possible using a two-pass algorithm and reports the result (e.g., "Layout partially pasted (5/9 slots)")
- **IntValueEntry Keyboard Input** — Click the number value in clipboard history settings (Max History, Auto-delete timer) to type a value directly instead of using +/- buttons. Supports Enter to apply, ESC to cancel, and automatic clamping to valid ranges.

### Changes
- Paste now returns a structured `PasteResult` instead of showing messages directly, enabling the caller to compose contextual feedback
- Size-mismatch detection moved from `pasteLayout()` to the Ctrl+V handler for pre-check flexibility
- `pasteLayout()` now accepts optional override data for half-selection and cross-size paste scenarios

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
- **HandledScreenAccessor** — Now provides actual accessor methods for overlay positioning
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
