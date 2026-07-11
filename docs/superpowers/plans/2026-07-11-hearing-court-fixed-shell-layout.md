# Hearing Court Fixed-Shell Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the shared hearing court so its 720–820px canvas preserves fixed status and input docks, gives the transcript the only flexible center space, and replaces narrow-screen evidence columns with accessible overlay drawers.

**Architecture:** Keep all business state and API orchestration in `HearingCourtView.vue`. Rework only its hearing-specific markup and scoped styles: the central court becomes an explicit two- or three-row grid, the existing evidence rails become desktop columns or narrow overlay drawers without duplicating data, and abnormal transcript expansion is driven by a Unicode-character threshold in the view. Vitest mounts the real view with deterministic role, message, and evidence fixtures; the hearing-only Playwright fixture/spec now exercises rendered Chromium geometry through the shared browser-test foundation.

**Tech Stack:** Vue 3.5, Vue Test Utils 2.4, Vitest 3, jsdom 26, Playwright 1.61, scoped CSS, pnpm 11.7.

---

## Execution record

- Baseline gate: `corepack pnpm test -- src/views/disputes/HearingCourtView.test.js` exited 0 before implementation; the existing hearing file reported 28/28 passing and the command also completed the then-current full frontend suite at 203/203.
- RED: `corepack pnpm exec vitest run src/views/disputes/HearingCourtView.test.js` reported 36 tests with 8 expected failures. The failures were the eight newly added groups: fixed party/reviewer rows, narrow horizontal stages, 1499/1500/2000 threshold behavior, 50-message scroll responsibility, 100-evidence rail responsibility, accessible evidence drawers, the 1220px container breakpoint, and three-role shell semantics.
- GREEN: after the minimum hearing-specific implementation, the target command reported 36/36 passing. One intermediate 35/36 run exposed an over-broad CSS test regex that crossed rule boundaries; narrowing the assertion to a single CSS block restored the intended test without changing production behavior.
- Full verification: `corepack pnpm test` reported 30 files and 211 tests passing. `corepack pnpm build` completed successfully; Vite retained the repository's existing large-chunk warning.
- Browser verification follow-up: added `frontend/tests/browser/fixtures/hearing-court.fixture.js` and `frontend/tests/browser/hearing-court.layout.spec.js`, then exercised them through the shared Playwright foundation. The Chromium matrix covers 1260/1259, 1221/1220/1219, 1181/1180, 681/680, 390, 320, and 1024×600; it checks fixed-canvas containment, the three-stage and input-dock geometry, document-level horizontal overflow, exclusive evidence drawers, Tab/Shift+Tab focus looping, Escape focus restoration, and drawer-state cleanup when the workspace expands back to 1220px.
- Accessibility/error follow-up: the hearing ledger and retained settlement-dialog close controls now expose 44×44px targets. A 200-character unbroken error is rendered and verified without alert or document horizontal overflow.
- Follow-up verification: the final HearingCourtView target run reported 41/41 Vitest cases passing, the hearing-only Chromium run reported 18/18 Playwright cases passing, and the production Vite build completed successfully with only the repository's existing large-chunk warning.

## File map

- Create: `docs/superpowers/plans/2026-07-11-hearing-court-fixed-shell-layout.md`
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Create: `frontend/tests/browser/fixtures/hearing-court.fixture.js`
- Create: `frontend/tests/browser/hearing-court.layout.spec.js`

No shared room component, global stylesheet, package manifest, lockfile, CI, Playwright configuration, intake room, evidence room, overview, or outcome file is in scope.

## Fixed height budgets

Party center at the minimum canvas height:

```text
720px canvas - 28px vertical padding - 20px gaps - 122px status - 154px input
= 396px transcript row
```

At 820px the transcript row is 496px. A reviewer has no input dock and uses one 10px gap, returning the removed 154px row and gap to a 560px transcript row at the 720px minimum.

Each evidence rail uses:

```text
720px rail - 28px vertical padding - 20px gaps - 88px header - 48px footer
= 536px evidence-list row
```

At workspace width `>= 1220px`, use `282px minmax(620px, 1fr) 282px`. Below 1220px, preserve the center canvas and turn the existing rails into mutually exclusive overlay drawers. Drawer behavior includes backdrop/Escape closing, close-button focus on open, Tab containment, and launcher focus restoration.

## Task 1: Establish the baseline

**Files:**
- Verify: `frontend/package.json`
- Verify: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Install the declared dependencies**

Run in `frontend`:

```powershell
corepack pnpm install --frozen-lockfile
```

Expected: exit 0 without changing `package.json` or `pnpm-lock.yaml`.

- [ ] **Step 2: Run the untouched hearing target test**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
```

Expected: every pre-existing hearing test passes. Any pre-existing failure stops implementation with `BLOCKED`.

## Task 2: RED tests for the fixed center

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Add party/reviewer row-contract assertions**

Assert party `data-has-input-dock="true"`, reviewer `data-has-input-dock="false"`, reviewer class `courtroom-center--without-input`, `100dvh`, and exact party/reviewer grid rows:

```css
grid-template-rows: 122px minmax(0, 1fr) 154px;
grid-template-rows: 122px minmax(0, 1fr);
```

- [ ] **Step 2: Add a narrow three-stage assertion**

Require three rendered progress items and retain `repeat(3, minmax(0, 1fr))` below 680px; only the label/status inside each stage may stack.

- [ ] **Step 3: Verify RED**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
```

Expected: failures identify the current auto rows, missing reviewer mode, `100vh`, and one-column narrow stage rule.

## Task 3: RED tests for transcript thresholds and load

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Add 1499/1500/2000 Unicode-character cases**

Use `"陈".repeat(1499)`, `"陈".repeat(1500)`, and `"陈".repeat(2000)`. Assert 1499 renders fully without an expansion control; 1500 and 2000 receive `data-long-transcript="true"`, a collapsed preview, and an `aria-expanded="false"` button that reveals the full text.

- [ ] **Step 2: Add a 50-message pressure case**

Generate 50 alternating USER, MERCHANT, JUDGE, and JURY public messages. Assert all render, role class families remain present, and `.court-transcript__messages` remains the single transcript scroll rail.

- [ ] **Step 3: Keep the existing privacy case active**

The existing `SYSTEM_AUDIT_ONLY`/`A2A_INTERNAL` test must remain unchanged and pass in every target run.

- [ ] **Step 4: Verify RED**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
```

Expected: threshold and expansion assertions fail because all current messages render in full.

## Task 4: RED tests for evidence pressure and drawers

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Add 100 submitted evidence records**

Generate 50 USER and 50 MERCHANT records. Assert each pocket renders 50 cards, each complete filename is available through `title`, and `.evidence-pocket` is the evidence scroll rail.

- [ ] **Step 2: Add drawer accessibility coverage**

Assert both launchers expose `aria-controls`; opening the left drawer gives it `role="dialog"`, `aria-modal="true"`, and close-button focus; opening the right closes the left; Escape closes the right and restores launcher focus.

- [ ] **Step 3: Add breakpoint and rail-budget assertions**

Require a named inline-size container, a `max-width: 1219px` container query, `88px minmax(0, 1fr) 48px` rail rows, and removal of the old 1180px vertical-stack contract.

- [ ] **Step 4: Verify RED**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
```

Expected: drawer controls/focus, filename titles, container breakpoint, and fixed rail rows are absent.

## Task 5: Implement the fixed-shell behavior

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Implement the 1500-character abnormal-report mode**

Use a constant threshold of 1500, expanded IDs keyed by public transcript item ID, and Unicode-safe `Array.from` helpers. Preserve sanitization and the audit-only filter.

- [ ] **Step 2: Implement one-side drawer state and keyboard behavior**

Use `null | "left" | "right"`, focusable launchers, overlay/backdrop closing, close buttons, Escape handling, Tab trapping, and launcher focus restoration. Reuse the existing evidence arrays and DOM; do not add API paths.

- [ ] **Step 3: Normalize evidence footers**

Group rail controls in fixed 48px footers. Keep supplement on the current-party rail, and keep ledger/completion actions reachable in the right rail/drawer. Expose the completion hint through accessible text/title without adding a fourth grid row.

- [ ] **Step 4: Implement the center and rail CSS contracts**

```css
.hearing-courtroom-page {
  box-sizing: border-box;
  height: clamp(720px, calc(100dvh - 150px), 820px);
  min-width: 0;
  min-height: 720px;
  max-height: 820px;
}

.courtroom-center {
  grid-template-rows: 122px minmax(0, 1fr) 154px;
}

.courtroom-center--without-input {
  grid-template-rows: 122px minmax(0, 1fr);
}

.party-evidence-rail {
  grid-template-rows: 88px minmax(0, 1fr) 48px;
}
```

Make errors overlay the center instead of creating a grid row. Add `overscroll-behavior: contain` and `scrollbar-gutter: stable` only to transcript/evidence lists.

- [ ] **Step 5: Implement the 1220px container breakpoint**

Name this view's `room-shell__workspace` inline-size container. Below 1220px, the center fills the canvas and inactive rails are hidden off-canvas. Below 680px, keep stages horizontal and keep textarea plus an approximately 104px submit column horizontal.

- [ ] **Step 6: Verify GREEN**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
```

Expected: every old and new hearing test passes, including privacy and business-action tests.

## Task 6: Full verification and commit

**Files:**
- Verify: `frontend/src/views/disputes/HearingCourtView.vue`
- Verify: `frontend/src/views/disputes/HearingCourtView.test.js`
- Verify: `docs/superpowers/plans/2026-07-11-hearing-court-fixed-shell-layout.md`

- [ ] **Step 1: Run fresh target and full tests**

```powershell
corepack pnpm test -- src/views/disputes/HearingCourtView.test.js
corepack pnpm test
```

- [ ] **Step 2: Build and check whitespace**

```powershell
corepack pnpm build
git diff --check
```

- [ ] **Step 3: Review scope**

```powershell
git status --short
git diff -- frontend/src/views/disputes/HearingCourtView.vue frontend/src/views/disputes/HearingCourtView.test.js frontend/tests/browser/hearing-court.layout.spec.js frontend/tests/browser/fixtures/hearing-court.fixture.js docs/superpowers/plans/2026-07-11-hearing-court-fixed-shell-layout.md
```

Expected: no changed file outside the five listed paths.

- [x] **Step 4: Run and record the hearing browser matrix**

`frontend/tests/browser/hearing-court.layout.spec.js` covers 1260/1259, 1221/1220/1219, 1181/1180, 681/680, 390, 320, and 1024×600. It asserts canvas geometry, exclusive scroll rails, drawer containment, focus looping and Escape behavior, cross-breakpoint dialog-state cleanup, 44px ledger controls, long unbroken error containment, and no document-level horizontal overflow.

- [ ] **Step 5: Commit**

```powershell
git add docs/superpowers/plans/2026-07-11-hearing-court-fixed-shell-layout.md frontend/src/views/disputes/HearingCourtView.vue frontend/src/views/disputes/HearingCourtView.test.js
git commit -m "feat: harden hearing court fixed-shell layout"
```
