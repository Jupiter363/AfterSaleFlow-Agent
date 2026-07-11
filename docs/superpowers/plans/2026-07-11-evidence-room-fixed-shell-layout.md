# Evidence Room Fixed-Shell Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep both evidence-room panels at a fixed 740px height while making the right-hand evidence list its only routine scroll rail and preserving readable evidence metadata at desktop and 320px widths for USER and MERCHANT actors.

**Architecture:** Keep the production change inside `EvidenceRoomView.vue`. The right evidence board becomes an explicit four-row grid (header, uploader, flexible evidence list, footer); errors are overlaid rather than inserted as a fifth layout row. Responsive behavior follows the actual `room-workspace` container: two columns at 1060px and above, stacked 740px panels below it, plus evidence-room-local `:deep()` overrides for the shared shell and conversation descendants at 360px and below.

**Tech Stack:** Vue 3 SFC, scoped CSS, Vue Test Utils, Vitest/jsdom, Playwright Chromium, pnpm/Vite.

---

## File map and layout budget

- Create: `docs/superpowers/plans/2026-07-11-evidence-room-fixed-shell-layout.md` — execution contract, RED/GREEN record, and verification checklist.
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue` — evidence-card DOM semantics and evidence-room-local fixed-shell/responsive CSS.
- Modify: `frontend/src/views/disputes/EvidenceRoomView.test.js` — DOM and source-layout contracts, 100-item stress fixture, 200-character filename, narrow header, and USER/MERCHANT coverage.
- Create: `frontend/tests/browser/evidence-room.layout.spec.js` — real-browser workspace boundary, viewport matrix, geometry, overflow, scroll-rail, and action-visibility coverage.
- Create: `frontend/tests/browser/fixtures/evidence-room.fixture.js` — deterministic actor-scoped Evidence API fixture for Playwright.
- Do not modify: `frontend/package.json`, `frontend/pnpm-lock.yaml`, CI, Playwright configuration, `frontend/src/styles.css`, `RoomShell.vue`, `ConversationStream.vue`, or any other room/view.

The 740px evidence board uses `box-sizing: border-box`, 18px top/bottom padding, and three 10px gaps on normal widths. After subtracting 36px padding and 30px gaps, its 674px track budget is allocated as 76px header + 86px uploader + 452px flexible list + 60px footer. At 360px and below, three 12px gaps and the same 18px padding leave 412px for the list after fixed rows of 88px header + 96px uploader + 72px footer. The flexible list is the sole routine `overflow-y: auto` descendant of the right board; evidence-library groups grow naturally inside that rail.

Breakpoints:

- `@container room-workspace (max-width: 1059px)`: stack the two fixed 740px panels so the page owns vertical scrolling; exactly 1060px and wider remains double-column.
- `max-width: 620px`: retain a horizontal `minmax(0, 1fr) auto` footer; simplify the uploader without stacking the footer.
- `max-width: 360px`: use the 88/96/flexible/72 row budget, hide decorative file/uploader art, place card status fields under file information, and locally restyle the room header into title / case-and-countdown / wrapping AI notice rows.

## Task 1: Establish the baseline

**Files:** No source changes.

- [x] **Step 1: Install the locked frontend dependencies**

Run from `frontend`:

```powershell
corepack pnpm install --frozen-lockfile
```

Expected: exit 0 with no `package.json` or `pnpm-lock.yaml` diff.

- [x] **Step 2: Run the existing evidence-room target test**

```powershell
corepack pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
```

Expected: all pre-existing `EvidenceRoomView` tests pass. If not, stop as `BLOCKED` before changing production code.

## Task 2: Add failing layout and stress contracts

**Files:**
- Modify: `frontend/src/views/disputes/EvidenceRoomView.test.js`

- [x] **Step 1: Add source-layout helpers and fixtures**

Read the SFC source with Node `readFileSync(new URL("./EvidenceRoomView.vue", import.meta.url), "utf8")`. Add a `stressCatalog(role, count, filename)` helper that creates submitted, actor-private evidence items with stable ids, verification states, confidence values, and long verification feedback.

- [x] **Step 2: Add focused DOM/source assertions**

Add tests that require:

```js
expect(source).toContain("--evidence-panel-height: 740px");
expect(source).toMatch(/grid-template-rows:\s*76px 86px minmax\(0, 1fr\) 60px/);
expect(source).toMatch(/\.evidence-board__list\s*\{[^}]*overflow-y:\s*auto/s);
expect(source).toMatch(/@container room-workspace \(max-width: 1059px\)/);
expect(source).not.toMatch(/@container room-workspace \(max-width: 1119px\)/);
expect(source).not.toMatch(/@media \(max-width: 1059px\)/);
expect(source).toMatch(/@media \(max-width: 360px\)/);
expect(source).toMatch(/grid-template-rows:\s*88px 96px minmax\(0, 1fr\) 72px/);
expect(source).toMatch(/:deep\(\.room-shell__header\)/);
```

Mount 100 evidence items for both `USER` and `MERCHANT` and assert all 100 cards are rendered inside `[data-evidence-list-scroll]`, while the fixed footer stays outside that scroll node. Mount a 200-character unbroken filename plus 200+ character unbroken Latin OCR/verification tokens, assert the filename has a complete `title`, the feedback occupies `[data-evidence-description]`, and opening the existing detail modal exposes the full filename and long text without panel, paragraph, modal-root, or document horizontal overflow. Require every `.evidence-modal__facts span` and `.evidence-modal__panel p` to use `min-width: 0`, wrap-safe whitespace, `overflow-wrap: anywhere`, and `word-break: break-word`. Assert each evidence card contains a wrap-capable `[data-evidence-status-row]` containing submission status, verification status, and confidence. In the 620px block, require an uploader `minmax(0, 1fr) auto` grid with its illustration/kicker hidden while the footer remains horizontal.

- [x] **Step 3: Run the target test and capture RED**

```powershell
corepack pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
```

Expected: FAIL because the explicit row budgets, strict 1060px boundary, 360px narrow-header contract, full filename/detail-facts wrapping, 620px uploader simplification, description row, and status-row markers do not yet exist.

## Task 3: Implement the minimal fixed-shell layout

**Files:**
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`

- [x] **Step 1: Reshape evidence cards by content type**

Keep the file icon and filename as the primary row. Move submission/owner, verification, and confidence labels into a flex-wrapping `.evidence-card__meta` marked `data-evidence-status-row`; add the complete filename as `title`; render existing clerk verification feedback in a full-width `.evidence-card__description` marked `data-evidence-description`. Do not change evidence data fetching or mutation behavior.

- [x] **Step 2: Encode the four-row height budget**

Set `.evidence-board` to `position: relative` and:

```css
grid-template-rows: 76px 86px minmax(0, 1fr) 60px;
```

Keep `.evidence-board__list` as the only normal right-board `overflow-y: auto` rail. Ensure board children, libraries, cards, file info, metadata, and long descriptions use `min-width: 0`; use `white-space: pre-wrap`, `overflow-wrap: anywhere`, and `word-break: break-word` for natural-language descriptions. Convert `.evidence-error` into an absolute toast-style overlay so it cannot add a grid row.

- [x] **Step 3: Implement responsive contracts**

Replace the 980px viewport stacking breakpoint with `@container room-workspace (max-width: 1059px)`, preserving double columns from 1060px of actual workspace width upward. At 620px and below, change the uploader to `minmax(0, 1fr) auto`, hide its decorative illustration/kicker, and keep the footer horizontal. At 360px and below, apply the 88/96/flexible/72 grid rows, hide decorative file art, move card metadata below file information, remove fixed minimum width from the completed-state control, and use local `:deep()` rules to give the shared room header, AI notice, countdown, and evidence-room conversation descendants `min-width: 0` and wrap-safe behavior. Apply the same wrap-safe contract to every detail-modal fact cell and long detail heading.

- [x] **Step 4: Run the target test and capture GREEN**

```powershell
corepack pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
```

Expected: all evidence-room tests pass for both roles and stress fixtures.

## Task 4: Refactor and verify

**Files:** Only the Evidence production view, colocated Vitest, Evidence Playwright spec/fixture, and this plan listed in the file map.

- [x] **Step 1: Review the scoped diff**

Confirm no shared component, global stylesheet, package, lockfile, CI, Playwright, or unrelated room file changed. Confirm every new CSS selector is evidence-room-local.

- [x] **Step 2: Run fresh verification**

```powershell
corepack pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
corepack pnpm exec playwright test tests/browser/evidence-room.layout.spec.js
corepack pnpm test
corepack pnpm build
git diff --check
```

Run the Evidence Playwright spec against the shared browser-test foundation. It must measure the exact `room-workspace` 1060/1059 switch and the viewport matrix `1121/1120`, `1061/1060`, `981/980`, `581/580`, `390`, `320`, and `1024x600`; every case keeps 740px panels, visible uploader/footer actions, the evidence list as the only right-board scroll rail, and no document horizontal overflow.

- [x] **Step 3: Request code review and address findings**

Ask a reviewer to compare the diff with this plan and the approved spec. Fix Critical/Important findings, rerun the full verification set, and record any remaining manual-browser checks.

- [x] **Step 4: Commit the scoped change**

```powershell
git add docs/superpowers/plans/2026-07-11-evidence-room-fixed-shell-layout.md frontend/src/views/disputes/EvidenceRoomView.vue frontend/src/views/disputes/EvidenceRoomView.test.js frontend/tests/browser/evidence-room.layout.spec.js frontend/tests/browser/fixtures/evidence-room.fixture.js
git commit -m "feat: harden evidence room fixed-shell layout"
```

## Plan self-review

- Spec coverage: fixed 740px panels, single list scroll rail, strict double columns at 1060px of actual workspace width and above, the full viewport matrix, 320px multi-line header, 100 evidence items, 200-character unbroken filename in cards and detail facts, long AI/verification text, simplified 620px uploader, horizontal narrow footer, and USER/MERCHANT behavior are each tied to a test and implementation step.
- Scope: only the evidence-room view, its colocated test, Evidence-only browser spec/fixture, and this plan are writable; shared shell/conversation behavior is handled by the already-approved shared foundation plus evidence-room-local deep selectors.
- Browser coverage: the Evidence-only spec and fixture are present; package, lockfile, global smoke fixture, and Playwright configuration remain owned by the shared browser-test foundation.
- Placeholder scan: no TBD/TODO or unspecified implementation steps remain.

## Execution record

- Dependency baseline: `corepack pnpm install --frozen-lockfile` exited 0 and left `frontend/package.json` plus `frontend/pnpm-lock.yaml` unchanged.
- Existing target baseline: `EvidenceRoomView.test.js` passed 29/29 tests before production changes.
- RED: after adding the layout/stress contracts, the target suite ran 34 tests with 5 expected failures and 29 passes. Failures identified the missing four-row budget, status-row DOM contract, complete filename/description affordances, and 360px wrap-safe header/conversation rules. An earlier source-loading collection error was corrected before accepting RED.
- GREEN: after the minimal `EvidenceRoomView.vue` implementation, the target suite passed 34/34 tests. Self-review then isolated the compact-footer/error-overlay contract: temporarily reverting those two rules produced a focused 1/1 RED, and restoring them produced a focused 1/1 GREEN. The final target suite passed 35/35 tests.
- Full verification: frontend Vitest passed 30 files and 209 tests; Vite production build exited 0 with only the pre-existing large-chunk advisory; `git diff --check` exited 0 with only the repository's LF-to-CRLF conversion warning.
- Scope audit: only this plan, `EvidenceRoomView.vue`, `EvidenceRoomView.test.js`, and the Evidence-only Playwright spec/fixture changed. No package, lockfile, CI, Playwright configuration, global smoke fixture, global style, shared room component, or unrelated page was modified.
- Review handoff: an independent reviewer was requested, but all collaboration slots were occupied by the parallel room rollout. The parent task explicitly directed this branch to proceed after self-review and will run cross-agent specification/quality review after commit.
- Review follow-up RED/GREEN: on committed baseline `b59c4db`, the three new review contracts produced 3 failures and 32 passes: detail-modal fact wrapping, 620px uploader simplification, and the then-current strict workspace boundary. Each focused test passed after its isolated fix; the final target suite passed 35/35, the full frontend suite passed 30 files/209 tests, the production build exited 0, and `git diff --check` exited 0.
- Unified workspace-boundary RED: changing the Vitest contract to `max-width: 1059px` produced the expected focused failure (1 failed, 34 passed) while production still declared `max-width: 1119px`. With the shared `RoomShell` container foundation active, the exact-workspace Playwright assertion also failed correctly at 1060px (`expected 2 columns, received 1`).
- Unified workspace-boundary GREEN: after changing only the Evidence container query to `max-width: 1059px`, the colocated Vitest passed 35/35 and the Evidence Playwright spec passed 17/17. The browser run covered exact workspace 1060/1059, viewport `1121/1120`, `1061/1060`, `981/980`, `581/580`, `390`, `320`, and `1024x600`, both USER/MERCHANT 100-card rails, 620/619 compact controls, long filenames, detail modal containment, operation visibility, and no document horizontal overflow.
- Detail-modal long-token RED/GREEN: a new Vitest source contract failed 1/35 while `.evidence-modal__panel p` lacked wrap containment, and the 320px Chromium case failed with `panel.scrollWidth > panel.clientWidth` for a 240-character unbroken OCR/verification token. Adding `min-width: 0`, `white-space: pre-wrap`, `overflow-wrap: anywhere`, and `word-break: break-word` made the focused Vitest 35/35, the focused Chromium case 1/1, and the full Evidence Playwright spec 17/17.
- Final verification: a fresh colocated Vitest run passed 35/35, the final full Evidence Chromium spec passed 17/17, `corepack pnpm build` exited 0 with only the pre-existing large-chunk advisory, and `git diff --check` exited 0.
