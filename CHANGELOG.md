# Changelog

## [1.10.0] - 2026-03-30

### Visual Overhaul
- **Vanilla-Style Slot Rendering** — Preview grids in the clipboard history browser now use vanilla Minecraft slot sprites (`drawGuiTexture`) instead of simple colored fills. Every slot in every preview type (containers, player inventory, bundles, crafters, hoppers, furnaces) renders with the authentic beveled-border slot appearance.
- **Native-Scale Item Rendering** — Items in preview grids now render at their native 16×16 pixel size instead of being scaled down with the grid. Stack count numbers, enchantment glint, and durability bars are all crisp and readable. A `PREVIEW_SCALE` constant controls the slot frame size (default 1.0 for full-size vanilla slots; set to 0.6 for a compact view where items overflow their smaller slot frames).
- **Player Inventory Layout Matches Vanilla** — Player inventory clipboard entries now display with a layout matching the real vanilla inventory screen: armor column on the left (with vanilla empty-slot placeholder sprites for helmet, chestplate, leggings, boots), offhand below armor, crafting area (output + 2×2 grid) in the top-right, main inventory 3×9 grid, and hotbar separated below. Crafting slots render as always-empty vanilla slots for visual accuracy.
- **Crafter Locked Slots** — Locked crafter slots in grid9 previews now use the vanilla disabled-slot sprite overlay instead of the old dark red fill.
- **Uniform Empty Slots** — Empty slots render as standard vanilla slot sprites with nothing inside. No more alternating dark/light color fills between empty and occupied slots.

## [1.9.1] - 2026-03-30

### Features
- **Clipboard Favorites** — Click the star icon on any clipboard history entry to mark it as a favorite. Favorited entries are never evicted by the max history limit, can't be individually deleted, and are skipped by "Clear All." A new "Favorites" tab in the clipboard history browser shows only favorited entries. Maximum 50 favorites.
- **Fill-Existing Scroll** — Hold the Misc modifier while scrolling to transfer items only into existing partial stacks on the destination side. Items with no matching partial stack are skipped. Uses manual click-merging to prevent overflow into empty slots.
- **Leave-1 + Fill-Existing Scroll** — Hold AllBut1 + Misc while scrolling to combine leave-1 (right-click 1 back) with fill-existing (manual merge into partials only).

### Bug Fixes
- **Fixed fill-existing scroll overflow** — Fill-existing scroll originally used QUICK_MOVE which could place items in empty slots. Replaced with manual click-merging algorithm (pick up → left-click partial stacks → put remainder back).
- **Fixed leave-1 + fill-existing placing items in empty slots** — Pre-check now gates entire leave-1+fill-existing sequence.
- **Fixed stale ItemStack reference during fill-existing scroll** — Source ItemStack is now copied before pickup to prevent stale reference in destination comparisons (pickup mutates the slot).

### Changes
- Scroll transfer now supports 4 modes: flush (bare scroll), leave-1 (AllBut1+scroll), fill-existing (Misc+scroll), leave-1+fill-existing (AllBut1+Misc+scroll)
- Updated tooltips for Scroll Transfer and Fill Existing Stacks entries in the config screen

## [1.9.0] - 2026-03-30

### Features
- **Three Global Modifier Keys** — New `miscModifierKey` global for throw-half and fill-existing actions, alongside the existing `allBut1Key` and `only1Key`. OS-specific defaults: Mac uses Cmd/Ctrl/Option, Windows/Linux uses Alt/Ctrl/R.Alt.
- **Bundle Keys** — New `bundleAllBut1Key` and `bundleOnly1Key` config fields for bundle-specific modifier key overrides. Two-level inheritance: per-operation override → bundle pair → global.
- **Single-Key Inheritance** — `throwAllBut1Key`, `throwHalfKey`, `fillExistingKey`, and `scrollLeave1Key` now default to -1 (inherit from parent global) instead of hardcoded GLFW values. Inheritance chains: throwAllBut1/scrollLeave1 → allBut1Key, throwHalf/fillExisting → miscModifierKey.
- **Config Screen Tooltip System** — Every config entry now has a hover tooltip (300ms delay, renders below entry or above if near screen bottom). Tooltips trigger on the text label area only, not on buttons.
- **Collapsible Advanced Options** — Per-tweak key overrides moved to a collapsible "Advanced Options" section at the bottom of the Hotkeys tab (collapsed by default, click header to expand/collapse).
- **Editable Open Config Key** — The config screen open key (default: K) is now editable in the Hotkeys tab instead of being a static info text.
- **Independent Throw All-But-1 Toggle** — New `enableThrowAllBut1` config field (default: true) allows enabling/disabling throw-allBut1 separately from throw-half.
- **Debug Ring Buffer** — 200-line circular buffer captures debug log output. "Copy Debug Log" button in the Debug tab copies the buffer to the system clipboard (visible only when debug logging is ON).
- **Report Bug Button** — "Report Bug" button in the Debug tab opens the GitHub issues page in the browser. Always visible.

### Changes
- Config screen reduced from 5 tabs to 4 tabs: All, Tweaks, Hotkeys, Debug. Advanced tab removed; content moved to collapsible section in Hotkeys.
- **All tab** simplified: Modifier Keys at top, then feature toggles, copy/paste settings, clipboard settings, bundle keys, other tweaks. No advanced overrides.
- **Tweaks tab** reorganized: Enable/Disable Tweaks, Copy/Paste, Clipboard Settings, Other Tweaks.
- **Hotkeys tab** reorganized: Modifier Keys, Bundle Keys, Copy/Paste Layout, Open Config Key, Advanced Options (collapsible).
- Override entries now show "AB1: Global" / "O1: Global" labels when set to -1 (inherited). Bundle keys show resolved parent key name.
- Section renames: "Modifier-Pair Tweaks" → "Enable/Disable Tweaks", "Single-Key Tweaks" → "Other Tweaks", "Scroll Wheel Transfer" → "Scroll Transfer", "Global Modifier Keys" → "Modifier Keys", "Advanced Overrides" → "Advanced Options".
- New entry types: `OverridePairEntry`, `OverrideSingleEntry`, `ActionButtonEntry`. Removed: `TweakKeyModeEntry`, `TweakKeyPairEntry`, `HeaderEntry`.

### Removed
- `clipboardExpiryPlaytimeHours` config field and all expiry logic — max history count is the only limit now.
- `enableHotbarModifiers` config field — hotbar modifiers are always enabled.

## [1.8.0] - 2026-03-29

### Features
- **Single-Level Paste/Cut Undo** — Press Ctrl+Z (or configured `undoKey`) after a paste or cut to restore the pre-operation state. Covers all container types (standard, grid9, crafter, crafting table, hopper, furnace, player-only). Single-level: each new paste/cut overwrites the previous undo snapshot. In-memory only — snapshot is cleared when the inventory screen is closed.
- **Configurable Undo Key** — New `undoKey` config field (default: Ctrl+Z legacy). Set a specific GLFW key code to use a single-key undo bind instead.
- **Undo Overlay Messages** — "Paste undone" (green) on full restore, "Paste partially undone (X/Y slots)" (yellow) on partial restore, "Nothing to undo" (red) when no snapshot exists.

### Bug Fixes
- **Fixed source exhaustion in undo executor** — Sequential find-and-move mutated handler state, causing later moves to fail. Replaced with pre-planned move algorithm using a claimed sources set.
- **Fixed cursor stash slot used as undo source** — Stash slot is now added to claimed sources before planning, preventing it from being consumed during item restoration.
- **Fixed correct slots used as undo sources** — Undo no longer grabs items from slots that already match their snapshot state. Source search restricted to diff-only slots.
- **Fixed stash slot snapshot not restored** — Added Phase 4 post-cursor-restore to handle the stash slot's original item after cursor pickup.
- **Fixed Phase 3+4 cursor conflict** — Merged cursor restore and stash slot restore into a single sequence with temp-slot juggling to avoid left-click conflicts with an occupied cursor.
- **Fixed crafter undo lock phase ordering** — Paste does locks→items, so undo now does items→locks (reverse order), allowing items to be picked up from still-unlocked slots before toggling.
- **Fixed count mismatch from even distribution** — Undo now uses type-only matching with multi-source merging instead of `ItemStack.areEqual()`, handling cases where paste redistributed stack counts (e.g., 62→4×15).
- **Fixed left-click depositing entire stack during undo** — Undo now uses right-click loop for exact count placement when the source stack is larger than the target needs.

### Notes
- Bundle paste undo is not supported (deferred post-2.0 — bundle undo requires component manipulation, not slot rearrangement)
- Cut in player-only inventory does not capture an undo snapshot (no items are moved)
- If items are manually moved between paste/cut and undo, undo reports partial restoration as expected

## [1.7.0] - 2026-03-29

### Features
- **Container Classification System** — New `ContainerClassifier` categorizes open containers into 8 types: Standard, Grid9 (dispensers, droppers, crafters, crafting tables), Hopper, Furnace, Crafter, Crafting Table, Player-Only, and Incompatible. Classification fires on clipboard key press and gates copy/paste/cut behavior per container type.
- **Incompatible Container Blocking** — Copy, paste, and cut are blocked in 10 incompatible containers: villager trading, anvil, enchanting table, grindstone, loom, cartography table, beacon, brewing stand, stonecutter, and smithing table. Shows "Incompatible" overlay message. Clipboard history browser (Shift+Tab) still opens normally in these containers.
- **Small Container Clipboard Types** — Three new clipboard entry types for small containers: `grid9` (dispensers, droppers, crafters, crafting tables — 9 slots), `hopper5` (hoppers, hopper minecarts — 5 slots), and `furnace2` (furnaces, blast furnaces, smokers — 2 slots: input + fuel, output excluded). Each type has its own active index in the clipboard history.
- **Container-Specific Copy** — Each container type copies only the relevant slots: crafting tables exclude the output slot (slot 0) and remap inputs to keys 0-8; furnaces exclude the output slot (slot 2) and copy only input + fuel; crafters capture lock state as `SlotData.LOCKED` sentinel for disabled slots.
- **Container-Specific Paste** — Cross-type paste guards ensure clipboard entries only paste into compatible containers (grid9 → grid9/crafter/crafting table, hopper5 → hoppers, furnace2 → furnaces). Mismatches show descriptive messages.
- **Crafter Lock-State Support** — Copying a crafter captures which slots are disabled. Pasting into a crafter toggles each slot's lock state to match the clipboard before placing items. Pasting a crafter clipboard into a non-crafter container skips locked entries and shows "(N locked slots skipped)".
- **Even Distribution for Small Containers** — Grid9, hopper5, and furnace2 pastes distribute items evenly across target slots instead of maximizing individual stack quantities. Algorithm: group by item type, calculate floor(available / numSlots), distribute remainder in reading order. Handles re-paste correctly by including items already in target slots. Standard containers (chests) continue to use quantity maximization.
- **Size-Mismatch Paste Modes** — New `sizeMismatchPasteMode` config (int, default 1): 0 = Hover Position (cursor Y relative to container midpoint), 1 = Menu Selection (HalfSelectorOverlay with preview grids and A/D/click selection), 2 = Arrow Keys (hold Up/Down while pressing paste). Only affects the 27→54 direction; 54→27 always uses Menu mode for preview visibility.
- **HalfSelectorOverlay Mouse Click Support** — Clicking a preview grid in the half-selector overlay now selects that half. Clicking outside dismisses the overlay. Implemented via mouseClicked HEAD injection in HandledScreenMixin.
- **HalfSelectorOverlay Direction Support** — The overlay now supports both 54→27 and 27→54 directions with appropriate labels for each.
- **Clipboard History Browser Updates** — Grid9 entries display as a 3×3 grid with locked slots in dark red. Hopper5 entries display as a 5×1 horizontal row. Furnace2 entries display as 2×1 side-by-side slots. Slot count summaries show "X/9 slots taken", "X/5 slots taken", "X/2 slots taken" as appropriate.

### Bug Fixes
- **Fixed overlay background stretching** — `InvTweaksOverlay` background width now fits text content instead of stretching across the screen at small GUI scales
- **Fixed Yarn name for dispenser/dropper handler** — `Generic3x3ScreenHandler` corrected to `Generic3x3ContainerScreenHandler` (1.21.11 Yarn)
- **Fixed crafter lock toggle mechanism** — Changed from `SlotActionType.PICKUP` to `interactionManager.clickButton(syncId, slotIndex)`, matching vanilla crafter behavior
- **Fixed even distribution counting** — Target slots excluded from available item count, quantity overrides respected in paste loop, `alreadyMatches` shortcut bypassed during even distribution, source slots re-ranked per target slot, filled targets protected from sourcing, items already in target slots included in total pool calculation
- **Fixed ambiguous overload** — `pasteLayout(handler, isPlayerOnly, null)` disambiguated with explicit cast to resolve `Map<Integer,SlotData>` vs `ContainerCategory` overload
- **Fixed crafter-to-dispenser paste** — Override data from filtered LOCKED entries no longer bypasses displacement logic
- **Fixed hover mode Y detection** — Midpoint calculation now uses container slot area instead of full GUI background height
- **Fixed 27→54 bottom-half paste offset** — Slot offset logic for bottom-half paste now correctly adds +27 to target indices

## [1.6.1] - 2026-03-22

### Bug Fixes
- **Fixed cut showing two overlay messages** — Cut on a bundle no longer shows both a copy message and a cut message. The copy step is now silent when triggered by cut.
- **Fixed cut message formatting** — Bundle cut message now shows "Bundle layout copied (cut not supported)" in green instead of orange
- **Fixed bundle item tooltips in clipboard history** — Bundles stored as items inside clipboard entries now show their graphical contents preview on hover instead of a text-only tooltip
- **Fixed player inventory overlay not rendering** — Overlay messages ("Layout copied", etc.) now appear correctly in the player-only inventory screen (E key). Previously, `InventoryScreen`'s render path bypassed the mixin injection point. Changed injection from `render` to `renderMain` at TAIL.
- **Improved death snapshot paste displacement** — Smart displacement now checks if displaced items can go directly to their intended target slot before falling back to empty non-target slots, reducing cascading moves

### Changes
- **Dyed bundle label color** — Bundle entries in the clipboard history browser now reflect the bundle's dye color in their label text (e.g., a red-dyed bundle shows a red label instead of the default orange)
- **License change** — Switched from MIT to a custom source-available license (ARR) as of v1.6.0. See LICENSE.md for full terms. Versions prior to 1.6.0 remain under the MIT License.

## [1.6.0] - 2026-03-22

### Features
- **Bundle Clipboard** — Copy and paste bundle contents as clipboard layouts. Hover a bundle in any open inventory and press the copy key to snapshot its contents. Press paste while hovering a bundle to fill it with items matching a saved bundle layout.
- **Bundle Copy** — Copies all items from a bundle into the clipboard as a "bundle" type entry. Serializes full component data (enchantments, dyes, custom names, nested bundles). Empty bundles show "Bundle is empty" instead of creating an entry. Duplicate detection works the same as container/player entries.
- **Bundle Paste** — Fills a bundle with items from available inventory slots to match a saved bundle layout. Items are inserted via simulated left-click interactions to maintain server sync. Handles partial fills (bundle weight limit reached) and missing items gracefully with descriptive overlay messages.
- **Bundle Cut** — Copies the bundle layout without extracting items. Shows "Cut not supported on bundles, layout copied" since programmatic extraction is complex and error-prone.
- **Bundle Clipboard History** — Bundle entries appear in the clipboard history browser with a dynamic grid that scales based on item count (using ceil(sqrt(N)) columns, clamped to 2-8). Bundle entries are colored orange to distinguish them from container (yellow) and player (aqua) entries. Summary shows "X items" instead of "X/Y slots taken".
- **Third Active Index** — The clipboard system now maintains a separate `activeBundleIndex` alongside `activeContainerIndex` and `activePlayerIndex`. Selecting a bundle entry in the history browser sets the bundle index without affecting container/player selection.
- **Bundle Label Format** — Bundle entries in the history browser show labels like "Bundle: Red Bundle (7 items)" for renamed bundles or "Bundle (3 items)" for unnamed ones.

## [1.5.1] - 2026-03-22

### Features
- **Clipboard Deduplication** — Detects identical layout copies (same contents + same container title) and replaces the older entry instead of creating a duplicate
- **Configurable Clipboard History Keybind** — Clipboard history key is now configurable (`clipboardHistoryKey`, default: legacy Shift+Tab)
- **Overlay Message Suppression Toggle** — New `showOverlayMessages` config field (default: true) to suppress overlay messages ("Layout copied", etc.)
- **Custom Copy/Paste/Cut Keybinds** — `copyLayoutKey`, `pasteLayoutKey`, `cutLayoutKey` config fields (default: legacy Ctrl+C/V/X). Replaces hardcoded key detection.
- **New "Copy/Paste Layout" Section in Hotkeys Tab** — 4 KeyBindEntry rows for clipboard history, copy, paste, and cut keybinds. Removed old Ctrl+C/V/X info text rows.
- **Multi-Select Deletion in Clipboard History** — Ctrl/Cmd+click toggle, Shift+click range select, bulk delete with confirmation
- **Vanilla-Style Hover Tooltips in Clipboard History** — Item tooltips in the preview grid now use vanilla tooltip rendering with full component data, respecting the F3+H advanced tooltip toggle

### Bug Fixes
- **Fixed `reconstructStack` Log Spam** — Cached failed component strings so each unique failure logs WARN once instead of every frame (previously 600+ lines/sec). Returns fallback ItemStack (item + count, no components) for preview.
- **Fixed Multi-Select Count Mismatch** — Ctrl+clicking now promotes the highlighted entry into the multi-select set and clears the single highlight, preventing off-by-one selection counts
- **Fixed Death Snapshot Paste Regression** — Armor slots 5–8 and offhand slot 45 are now excluded from displacement target selection in `findEmptyNonTargetSlot` and `findAnyEmptySlot`. Previously, empty armor slots after death were picked as displacement targets, rejecting non-equippable items.

## [1.4.1] - 2026-03-22

### Bug Fixes
- **Paste Bounce with Cursor Item** — Fixed pass 2 retry logic cannibalizing correctly-filled target slots when cursor holds items, causing items to bounce between two slots on repeated Ctrl+V. Pass 2 now protects slots filled in pass 1.
- **"Already Matches" Blocking Quantity Maximization** — Paste now checks whether stacks could be topped up even when all item types are correct, instead of showing "Layout already matches!" when surplus items are available
- **Removed Orphaned Config Fields** — Removed 6 dead per-tweak key override fields for throwHalf, fillExisting, and scrollTransfer that had no effect (these tweaks use single dedicated keys, not the allBut1/only1 pair system). Old configs are handled gracefully.
- **Ungated Scroll Debug Log** — `[IT:SCROLL-RAW]` log line now respects the `enableDebugLogging` setting instead of firing on every scroll event
- **Verbose Serialize Logging** — Removed per-item debug log lines from `serializeComponents()` that produced excessive output when debug logging was enabled
- **Duplicate Config Screen Branches** — Removed duplicate `else if` branches in key capture `applyCapture()` and `cancelCapture()` methods
- **Overlay Renders Behind Tooltips** — Overlay messages ("Layout pasted", etc.) now render behind item tooltips instead of on top. Moved overlay rendering from `afterRender` callback to a `@Inject` on `HandledScreen.render()` before cursor stack rendering.
- **Scroll Leave-1 Works With Full Containers** — Scroll leave-1 mode now searches the destination side for temp slots when the source side is full, instead of failing silently. Both sides full still fails gracefully.
- **Quantity Maximization Message** — Paste now shows "Stacks topped up" when proceeding for quantity-only topping, instead of the misleading "Layout almost full" message

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
