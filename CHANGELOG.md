# Changelog

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
