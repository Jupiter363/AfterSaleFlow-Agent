# Intake Room Fixed-Shell Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持接待室左右主卡 740px 固定外框的前提下，重排卷宗空间、让聊天消息自然增高并只在消息轨道滚动，彻底修复 580px 原始陈述高度归零、核验项裁切和 320px 横向溢出。

**Architecture:** 左侧聊天卡采用固定案情头、弹性消息轨道和固定输入台；右侧采用 44px 标题、44px 状态、412px 争议详情、112px 核验重点和 52px 操作槽。接待室默认单列，RoomShell 工作区容器宽度达到 1060px 才恢复双列。首次引入可测量溢出的 `ExpandableText` 和 Playwright Chromium 布局门禁，但不改变其他房间布局。

**Tech Stack:** Vue 3.5、Vite 6、Vitest 3、Vue Test Utils、Playwright 1.61 Chromium、pnpm 11.7、GitHub Actions。

---

## Files

**Create:**

- `frontend/src/components/common/ExpandableText.vue`
- `frontend/src/components/common/ExpandableText.test.js`
- `frontend/src/styles.test.js`
- `frontend/playwright.config.js`
- `frontend/tests/browser/fixtures/intake-room.fixture.js`
- `frontend/tests/browser/intake-room.layout.spec.js`

**Modify:**

- `frontend/package.json`
- `frontend/pnpm-lock.yaml`
- `frontend/src/components/room/RoomShell.vue`
- `frontend/src/components/room/RoomShell.test.js`
- `frontend/src/components/room/ConversationStream.vue`
- `frontend/src/components/room/ConversationStream.test.js`
- `frontend/src/views/disputes/IntakeRoomView.vue`
- `frontend/src/views/disputes/IntakeRoomView.test.js`
- `frontend/src/styles.css`
- `.github/workflows/quality-gate.yml`
- `.gitignore`

## Task 0: Isolate the intake-room branch

- [ ] **Step 1: Create an isolated worktree from current main**

Invoke `superpowers:using-git-worktrees`, then run from repository root:

```powershell
git fetch origin
git worktree add .worktrees/layout-intake-room -b codex/layout-intake-room main
```

Expected: `.worktrees/layout-intake-room` is on `codex/layout-intake-room`, and the original workspace remains on `main`.

- [ ] **Step 2: Confirm the baseline is clean**

```powershell
git -C .worktrees/layout-intake-room status --short --branch
```

Expected: branch header only, no modified or untracked files.

## Task 1: Add the minimal browser-layout test foundation

- [ ] **Step 1: Install the exact Playwright dependency**

```powershell
Set-Location .worktrees/layout-intake-room/frontend
corepack prepare pnpm@11.7.0 --activate
pnpm add -D -E @playwright/test@1.61.1
pnpm exec playwright install chromium
```

Expected: `frontend/package.json` and `frontend/pnpm-lock.yaml` contain `@playwright/test` version `1.61.1`; Chromium installation succeeds.

- [ ] **Step 2: Add browser test scripts**

Modify `frontend/package.json` so `scripts` is:

```json
{
  "dev": "vite --host 0.0.0.0",
  "build": "vite build",
  "test": "vitest run",
  "test:browser": "playwright test",
  "test:browser:intake": "playwright test tests/browser/intake-room.layout.spec.js",
  "test:browser:ui": "playwright test --ui",
  "test:browser:debug": "playwright test --debug"
}
```

- [ ] **Step 3: Create the Playwright configuration**

Create `frontend/playwright.config.js`:

```js
import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/browser",
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  outputDir: "test-results",
  reporter: process.env.CI
    ? [
        ["line"],
        ["html", { open: "never", outputFolder: "playwright-report" }],
      ]
    : "list",
  use: {
    baseURL: "http://127.0.0.1:4173",
    locale: "zh-CN",
    colorScheme: "light",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  webServer: {
    command: "pnpm exec vite --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: "chromium-desktop",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 1440, height: 1100 },
      },
    },
  ],
});
```

- [ ] **Step 4: Create a deterministic intake fixture**

Create `frontend/tests/browser/fixtures/intake-room.fixture.js`:

```js
import { expect } from "@playwright/test";

export const CASE_ID = "CASE_INTAKE_LAYOUT";

const actor = { id: "user-local", role: "USER", label: "用户" };

function repeatToLength(seed, length) {
  return seed.repeat(Math.ceil(length / seed.length)).slice(0, length);
}

function buildMessages({ count = 2, unbrokenLength = 0 } = {}) {
  const messages = [
    {
      id: "MESSAGE_AGENT_1",
      sequence_no: 1,
      sender_role: "CUSTOMER_SERVICE",
      message_type: "AGENT_MESSAGE",
      message_text: "请补充说明签收时间、地点以及你希望平台如何处理。",
    },
    {
      id: "MESSAGE_USER_1",
      sequence_no: 2,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: "物流显示昨天下午签收，但我和家人都没有收到，希望核验后退款。",
    },
  ];
  for (let index = messages.length; index < count; index += 1) {
    messages.push({
      id: `MESSAGE_${index + 1}`,
      sequence_no: index + 1,
      sender_role: index % 2 === 0 ? "CUSTOMER_SERVICE" : "USER",
      message_type: index % 2 === 0 ? "AGENT_MESSAGE" : "PARTY_TEXT",
      message_text:
        unbrokenLength && index === count - 1
          ? "A".repeat(unbrokenLength)
          : repeatToLength("这是接待室布局压力消息。", 120),
    });
  }
  return messages;
}

function buildTurnMemory({
  summaryLength = 150,
  statementLength = 160,
  gapCount = 4,
} = {}) {
  const gaps = Array.from({ length: gapCount }, (_, index) =>
    repeatToLength(`第${index + 1}项核验重点需要核对签收主体与投递链路。`, 42),
  );
  return {
    turn_no: 4,
    case_intake_dossier: {
      dossier_version: 2,
      quality_score: 88,
      ready_for_next_step: true,
      admission_recommendation: "ACCEPTED",
      dossier: {
        schema_version: "intake_case_detail.v1",
        case_story: {
          title: "物流显示签收但用户称未收到商品",
          one_sentence_summary: repeatToLength(
            "订单物流已显示签收，但用户本人及家人均表示没有收到商品，签收链路仍待核验。",
            summaryLength,
          ),
        },
        references: {
          order_reference: "ORDER-1001",
          after_sales_reference: "AFTER-1001",
          logistics_reference: "SF1234567890",
        },
        party_positions: {
          user_claim: "用户称本人及家人均未收到商品，希望核验后退款。",
          merchant_claim: "",
        },
        claim_resolution: {
          initiator_role: "USER",
          requested_resolution: "REFUND",
          requested_amount: "299.00",
          normalized_statement: repeatToLength("用户请求核验签收链路并退款。", 40),
          original_statement: repeatToLength(
            "物流显示签收，但本人和家人都没有收到，希望平台核验后退款。",
            statementLength,
          ),
        },
        respondent_attitude: {
          respondent_role: "MERCHANT",
          attitude: "NOT_RESPONDED",
          position: repeatToLength("商家尚未明确回应退款诉求。", 40),
        },
        dispute_core_state: {
          core_conflict: "物流签收记录与用户未收到商品的陈述存在冲突。",
          facts_in_dispute: ["用户是否实际收到商品"],
          next_verification_focus: gaps,
        },
        dispute_focus: {
          core_issue: "签收记录与实际收货情况不一致",
          facts_to_verify: gaps,
        },
        missing_information: {
          blocking_gaps: gaps,
          nice_to_have_gaps: [],
          next_questions: [],
        },
        risk_assessment: {
          case_grade: "MEDIUM",
          risk_signals: ["签收事实存在冲突"],
        },
        intake_quality: {
          score: 88,
          threshold: 80,
          ready_for_next_step: true,
          improvement_reason: "",
        },
        admission: {
          recommendation: "ACCEPTED",
          reasoning: "现有信息足以进入证据阶段。",
          confidence: 0.88,
        },
      },
    },
  };
}

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

export async function installIntakeRoomFixture(page, options = {}) {
  const messages = buildMessages(options.messages);
  const turnMemory = buildTurnMemory(options.dossier);
  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, actor);

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const headers = request.headers();
    expect(headers).toMatchObject({
      "x-user-id": "user-local",
      "x-role": "USER",
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return fulfillJson(route, { unread_count: 0 });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}`
    ) {
      return fulfillJson(route, {
        id: CASE_ID,
        order_id: "ORDER-1001",
        after_sale_id: "AFTER-1001",
        logistics_id: "SF1234567890",
        initiator_role: "USER",
        title: "物流显示签收但用户未收到商品",
        description: "用户称物流已显示签收，但本人没有收到商品。",
        dispute_type: "SIGNED_NOT_RECEIVED",
        risk_level: "MEDIUM",
        current_room: "INTAKE",
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/rooms/INTAKE/messages`
    ) {
      return fulfillJson(route, messages);
    }
    if (
      request.method() === "GET" &&
      url.pathname ===
        `/api/disputes/${CASE_ID}/rooms/INTAKE/turn-memory/latest`
    ) {
      return fulfillJson(route, turnMemory);
    }
    if (
      request.method() === "GET" &&
      url.pathname === `/api/disputes/${CASE_ID}/events`
    ) {
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream" },
        body: ": deterministic-playwright-heartbeat\n\n",
      });
    }
    throw new Error(
      `Unhandled browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}
```

- [ ] **Step 5: Write the failing browser layout tests**

Create `frontend/tests/browser/intake-room.layout.spec.js`:

```js
import { expect, test } from "@playwright/test";
import {
  CASE_ID,
  installIntakeRoomFixture,
} from "./fixtures/intake-room.fixture.js";

async function pageHasHorizontalOverflow(page) {
  return page.evaluate(
    () =>
      document.documentElement.scrollWidth >
      document.documentElement.clientWidth + 1,
  );
}

async function assertInside(inner, outer) {
  const [innerBox, outerBox] = await Promise.all([
    inner.boundingBox(),
    outer.boundingBox(),
  ]);
  expect(innerBox).not.toBeNull();
  expect(outerBox).not.toBeNull();
  expect(innerBox.x).toBeGreaterThanOrEqual(outerBox.x - 1);
  expect(innerBox.y).toBeGreaterThanOrEqual(outerBox.y - 1);
  expect(innerBox.x + innerBox.width).toBeLessThanOrEqual(
    outerBox.x + outerBox.width + 1,
  );
  expect(innerBox.y + innerBox.height).toBeLessThanOrEqual(
    outerBox.y + outerBox.height + 1,
  );
}

test("keeps 740px shells and switches by the 1060px workspace contract", async ({ page }) => {
  await installIntakeRoomFixture(page);
  await page.goto(`/disputes/${CASE_ID}/intake`);
  const workspace = page.locator(".room-shell__workspace");
  const room = page.locator(".intake-room");
  const conversation = page.locator(".intake-room__conversation");
  const dossier = page.locator(".intake-dossier");

  await expect(conversation).toHaveCSS("height", "740px");
  await expect(dossier).toHaveCSS("height", "740px");

  await page.addStyleTag({ content: ".app-page{width:1060px!important}" });
  await expect.poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBeGreaterThanOrEqual(1060);
  const desktopColumns = await room.evaluate(
    (element) => getComputedStyle(element).gridTemplateColumns,
  );
  expect(desktopColumns.split(" ")).toHaveLength(2);

  await page.addStyleTag({ content: ".app-page{width:1059px!important}" });
  await expect.poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBeLessThan(1060);
  const compactColumns = await room.evaluate(
    (element) => getComputedStyle(element).gridTemplateColumns,
  );
  expect(compactColumns.split(" ")).toHaveLength(1);
  expect(await pageHasHorizontalOverflow(page)).toBe(false);
});

for (const viewport of [
  { width: 581, height: 843 },
  { width: 580, height: 843 },
  { width: 390, height: 844 },
  { width: 320, height: 568 },
]) {
  test(`preserves dossier slots at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await installIntakeRoomFixture(page, {
      dossier: { summaryLength: 150, statementLength: 160, gapCount: 4 },
    });
    await page.goto(`/disputes/${CASE_ID}/intake`);

    const dossier = page.locator(".intake-dossier");
    const origin = page.locator("[data-origin-statement-card]");
    const statement = page.locator("[data-origin-statement-text]");
    const actions = page.locator(".intake-dossier__actions--two-column");
    const buttons = actions.locator("button");

    await expect(dossier).toHaveCSS("height", "740px");
    expect(await statement.evaluate((element) => element.clientHeight))
      .toBeGreaterThan(0);
    await assertInside(origin, dossier);
    await assertInside(actions, dossier);

    const [firstButton, secondButton] = await Promise.all([
      buttons.nth(0).boundingBox(),
      buttons.nth(1).boundingBox(),
    ]);
    expect(firstButton).not.toBeNull();
    expect(secondButton).not.toBeNull();
    expect(Math.abs(firstButton.y - secondButton.y)).toBeLessThanOrEqual(1);

    for (const item of await page.locator("[data-verification-gap-item]").all()) {
      await assertInside(item, page.locator("[data-verification-gaps]"));
    }
    expect(await pageHasHorizontalOverflow(page)).toBe(false);
  });
}

test("keeps the message rail as the only scrolling region under pressure", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installIntakeRoomFixture(page, {
    messages: { count: 20, unbrokenLength: 1000 },
  });
  await page.goto(`/disputes/${CASE_ID}/intake`);

  const shell = page.locator(".intake-room__conversation");
  const rail = page.locator(".conversation-stream__messages");
  const composer = page.locator(".conversation-stream__composer");
  const textarea = composer.locator("textarea");

  await expect(shell).toHaveCSS("height", "740px");
  expect(await rail.evaluate((element) => element.scrollHeight > element.clientHeight))
    .toBe(true);
  const composerHeight = await composer.evaluate((element) => element.clientHeight);
  expect(composerHeight).toBeGreaterThanOrEqual(126);
  expect(composerHeight).toBeLessThanOrEqual(138);
  expect(await textarea.evaluate((element) => element.clientHeight)).toBe(72);
  await assertInside(composer, shell);
  expect(await pageHasHorizontalOverflow(page)).toBe(false);
});
```

- [ ] **Step 6: Run the browser tests and verify they fail for the existing layout**

```powershell
pnpm test:browser:intake
```

Expected: FAIL. At least the 580px original statement height, 1060px container switch, textarea fixed height, or 320px horizontal overflow assertion must fail.

- [ ] **Step 7: Add browser artifacts to gitignore and CI**

Append to root `.gitignore`:

```gitignore
**/playwright-report/
**/test-results/
**/blob-report/
```

Add after `Frontend tests and build` in `.github/workflows/quality-gate.yml`:

```yaml
      - name: Install Playwright Chromium
        working-directory: frontend
        run: pnpm exec playwright install --with-deps chromium

      - name: Frontend browser layout regression
        working-directory: frontend
        run: pnpm test:browser

      - name: Upload Playwright diagnostics
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: |
            frontend/playwright-report/
            frontend/test-results/
          retention-days: 7
```

- [ ] **Step 8: Commit the browser test foundation**

```powershell
git add frontend/package.json frontend/pnpm-lock.yaml frontend/playwright.config.js frontend/tests/browser .github/workflows/quality-gate.yml .gitignore
git commit -m "test: add intake browser layout gate"
```

## Task 2: Add the overflow-aware full-text preview component

- [ ] **Step 1: Write the failing component tests**

Create `frontend/src/components/common/ExpandableText.test.js`:

```js
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ExpandableText from "./ExpandableText.vue";

let resizeCallback;

class ResizeObserverMock {
  constructor(callback) {
    resizeCallback = callback;
  }
  observe() {}
  disconnect() {}
}

describe("ExpandableText", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows a full-text trigger only when the preview really overflows", async () => {
    const wrapper = mount(ExpandableText, {
      props: { text: "完整案情摘要", label: "案情摘要", lines: 4 },
    });
    const content = wrapper.get("[data-expandable-content]").element;
    Object.defineProperty(content, "clientHeight", { value: 72, configurable: true });
    Object.defineProperty(content, "scrollHeight", { value: 120, configurable: true });
    resizeCallback();
    await nextTick();
    expect(wrapper.get("[data-expandable-trigger]").text()).toBe("查看全文");
  });

  it("emits the complete text and exposes dialog state to assistive technology", async () => {
    const wrapper = mount(ExpandableText, {
      props: {
        text: "完整原始陈述",
        label: "原始陈述",
        lines: 4,
        expanded: true,
      },
    });
    const content = wrapper.get("[data-expandable-content]").element;
    Object.defineProperty(content, "clientHeight", { value: 72, configurable: true });
    Object.defineProperty(content, "scrollHeight", { value: 120, configurable: true });
    resizeCallback();
    await nextTick();
    await wrapper.get("[data-expandable-trigger]").trigger("click");
    expect(wrapper.emitted("open")[0]).toEqual([
      { label: "原始陈述", text: "完整原始陈述" },
    ]);
    expect(wrapper.get("[data-expandable-trigger]").attributes("aria-expanded"))
      .toBe("true");
  });
});
```

- [ ] **Step 2: Run the component test and verify it fails**

```powershell
pnpm test -- src/components/common/ExpandableText.test.js
```

Expected: FAIL because `ExpandableText.vue` does not exist.

- [ ] **Step 3: Implement the component**

Create `frontend/src/components/common/ExpandableText.vue`:

```vue
<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

const props = defineProps({
  text: { type: String, default: "" },
  label: { type: String, required: true },
  lines: { type: Number, default: 4 },
  expanded: { type: Boolean, default: false },
});

const emit = defineEmits(["open"]);
const content = ref(null);
const overflowing = ref(false);
let observer;

async function measure() {
  await nextTick();
  const element = content.value;
  overflowing.value = Boolean(
    element && element.scrollHeight > element.clientHeight + 1,
  );
}

function open() {
  emit("open", { label: props.label, text: props.text });
}

watch(() => [props.text, props.lines], measure);
onMounted(() => {
  observer = new ResizeObserver(measure);
  if (content.value) observer.observe(content.value);
  void measure();
});
onBeforeUnmount(() => observer?.disconnect());
</script>

<template>
  <div class="expandable-text" :style="{ '--expandable-lines': lines }">
    <p ref="content" class="expandable-text__content" data-expandable-content>
      {{ text }}
    </p>
    <button
      v-if="overflowing"
      type="button"
      data-expandable-trigger
      aria-haspopup="dialog"
      :aria-expanded="String(expanded)"
      @click="open"
    >
      查看全文
    </button>
  </div>
</template>

<style scoped>
.expandable-text {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 0;
  align-content: center;
  overflow: hidden;
}
.expandable-text__content {
  display: -webkit-box;
  min-width: 0;
  max-height: calc(1.55em * var(--expandable-lines));
  margin: 0;
  overflow: hidden;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--expandable-lines);
}
.expandable-text button {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 2px 0 2px 14px;
  color: #5f6fd8;
  background: linear-gradient(90deg, transparent, #fff 24%);
  border: 0;
  cursor: pointer;
  font-size: 11px;
  font-weight: 900;
}
</style>
```

- [ ] **Step 4: Run the component test and verify it passes**

```powershell
pnpm test -- src/components/common/ExpandableText.test.js
```

Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/components/common/ExpandableText.vue frontend/src/components/common/ExpandableText.test.js
git commit -m "feat: add overflow-aware text preview"
```

## Task 3: Make ConversationStream obey the fixed-shell contract

- [ ] **Step 1: Add failing ConversationStream tests**

In `frontend/src/components/room/ConversationStream.test.js`, add tests that mount a 1000-character unbroken message and assert the complete text remains in the DOM. Update the existing source-contract test to require:

```js
expect(source).toContain("overflow-x: hidden;");
expect(source).toContain("overscroll-behavior: contain;");
expect(source).toContain("scrollbar-gutter: stable;");
expect(source).toContain("height: 132px;");
expect(source).toContain("height: 72px;");
expect(source).toContain("max-height: 72px;");
expect(source).toContain("resize: none;");
expect(source).toContain("overflow-wrap: anywhere;");
expect(source).toContain("word-break: break-word;");
expect(source).toContain("white-space: pre-wrap;");
expect(source).toMatch(/@media \(max-width: 620px\)[\s\S]*?max-width: 94%;/);
```

The data test must use:

```js
const longMessage = "A".repeat(1000);
const wrapper = mount(ConversationStream, {
  props: {
    messages: [{
      id: "MESSAGE_LONG",
      sequence_no: 1,
      sender_role: "USER",
      message_type: "PARTY_TEXT",
      message_text: longMessage,
    }],
  },
});
expect(wrapper.get("[data-room-message] p").text()).toBe(longMessage);
```

- [ ] **Step 2: Run the focused tests and verify they fail**

```powershell
pnpm test -- src/components/room/ConversationStream.test.js
```

Expected: FAIL because textarea remains vertically resizable, composer has no fixed height, and long-token wrapping rules are missing.

- [ ] **Step 3: Implement the CSS contract**

Modify `frontend/src/components/room/ConversationStream.vue`:

```css
.conversation-stream {
  grid-template-rows: minmax(0, 1fr) auto;
  min-height: 0;
  overflow: hidden;
}
.conversation-stream__messages {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}
.conversation-stream__message {
  height: auto;
  min-width: 0;
  max-height: none;
}
.conversation-stream__message p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.conversation-stream__composer {
  height: 132px;
  overflow: hidden;
}
.conversation-stream__composer textarea {
  height: 72px;
  min-height: 72px;
  max-height: 72px;
  resize: none;
  overflow-y: auto;
}
@media (max-width: 620px) {
  .conversation-stream__message { max-width: 94%; }
}
```

Keep the existing readonly state as an `auto` row; do not assign 132px to `.conversation-stream__readonly`.

- [ ] **Step 4: Run the focused tests and verify they pass**

```powershell
pnpm test -- src/components/room/ConversationStream.test.js
```

Expected: all ConversationStream tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/components/room/ConversationStream.vue frontend/src/components/room/ConversationStream.test.js
git commit -m "fix: constrain intake conversation scrolling"
```

## Task 4: Preserve full dossier content without growing the shell

- [ ] **Step 1: Add failing IntakeRoomView behavior tests**

Modify `frontend/src/views/disputes/IntakeRoomView.test.js`:

1. Use 10 unique verification gaps and assert:

```js
expect(wrapper.findAll("[data-verification-gap-item]")).toHaveLength(4);
expect(wrapper.get("[data-verification-gap-overflow]").text()).toContain("另有 6 项");
```

2. Use a 300-character summary and 500-character original statement. Stub `ResizeObserver`, set preview `clientHeight/scrollHeight`, trigger the observer, then assert these controls exist:

```text
[data-dossier-fulltext-trigger="summary"] [data-expandable-trigger]
[data-dossier-fulltext-trigger="origin"] [data-expandable-trigger]
[data-dossier-fulltext-dialog]
[data-dismiss-dossier-fulltext]
```

Click the summary trigger and assert the dialog contains the full summary. Close it, click the origin trigger, and assert the dialog contains the full original statement.

Also assert the dialog receives focus, Escape closes it, and focus returns to the trigger.

- [ ] **Step 2: Run the IntakeRoomView test and verify it fails**

```powershell
pnpm test -- src/views/disputes/IntakeRoomView.test.js
```

Expected: FAIL because verification gaps are sliced before the total count is retained and there is no interactive full-text dialog.

- [ ] **Step 3: Keep the complete verification list and derive the preview**

In `IntakeRoomView.vue`, replace the existing `verificationGaps` computed with:

```js
const allVerificationGaps = computed(() => {
  const detail = caseDetailDossier.value || {};
  const missing = detail.missing_information || {};
  const respondentRole = resolveRespondentRole(
    detail,
    detail.respondent_attitude || {},
  );
  const respondentState = inferRespondentAttitude(
    detail,
    detail.respondent_attitude || {},
    respondentRole,
  );
  const candidates = [
    ...(Array.isArray(missing.blocking_gaps) ? missing.blocking_gaps : []),
    ...(Array.isArray(missing.nice_to_have_gaps) ? missing.nice_to_have_gaps : []),
    ...(Array.isArray(missing.next_questions) ? missing.next_questions : []),
    ...(Array.isArray(detail.dispute_focus?.facts_to_verify)
      ? detail.dispute_focus.facts_to_verify
      : []),
    ...qualityReasonToGaps(caseDetailQuality.value.reason),
    ...(claimStatus.value?.nextFocus || []),
    ...fallbackVerificationGaps(
      detail,
      respondentState.hasResponse,
      respondentRole,
    ),
  ];
  const seen = new Set();
  return humanizeDossierList(candidates, "")
    .map((item) => String(item || "").trim())
    .filter((item) => {
      if (!item || seen.has(item)) return false;
      seen.add(item);
      return true;
    });
});
const verificationGaps = computed(() => allVerificationGaps.value.slice(0, 4));
const hiddenVerificationGapCount = computed(() =>
  Math.max(0, allVerificationGaps.value.length - verificationGaps.value.length),
);
```

- [ ] **Step 4: Add full-text dialog state and handlers**

Import `nextTick` from Vue and import `ExpandableText`, then add:

```js
const dossierFulltext = ref(null);
const dossierFulltextDialog = ref(null);
let dossierFulltextReturnFocus = null;

async function openDossierFulltext(payload) {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = payload;
  await nextTick();
  dossierFulltextDialog.value?.focus();
}

async function openVerificationGaps() {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = {
    label: "下一步核验重点",
    items: allVerificationGaps.value,
  };
  await nextTick();
  dossierFulltextDialog.value?.focus();
}

async function closeDossierFulltext() {
  dossierFulltext.value = null;
  await nextTick();
  dossierFulltextReturnFocus?.focus();
}
```

Replace summary and origin text with `ExpandableText` instances using 5 and 4 preview lines. Add stable wrappers:

```vue
<ExpandableText
  data-dossier-fulltext-trigger="summary"
  data-dispute-detail-summary
  :text="caseCover.summary"
  :title="caseCover.summary"
  label="案情摘要"
  :lines="5"
  :expanded="dossierFulltext?.label === '案情摘要'"
  @open="openDossierFulltext"
/>
```

```vue
<ExpandableText
  data-dossier-fulltext-trigger="origin"
  data-origin-statement-text
  :text="subjectiveStatement.value || '待补充'"
  :title="subjectiveStatement.value || '待补充'"
  label="原始陈述"
  :lines="4"
  :expanded="dossierFulltext?.label === '原始陈述'"
  @open="openDossierFulltext"
/>
```

Add beside the gap count:

```vue
<button
  v-if="hiddenVerificationGapCount"
  type="button"
  data-verification-gap-overflow
  @click="openVerificationGaps"
>
  另有 {{ hiddenVerificationGapCount }} 项
</button>
```

Add before the existing intake error dialog:

```vue
<div
  v-if="dossierFulltext"
  ref="dossierFulltextDialog"
  class="intake-fulltext-dialog"
  data-dossier-fulltext-dialog
  role="dialog"
  aria-modal="true"
  aria-labelledby="intake-fulltext-title"
  tabindex="-1"
  @keydown.esc="closeDossierFulltext"
>
  <section class="intake-fulltext-dialog__card">
    <h3 id="intake-fulltext-title">{{ dossierFulltext.label }}</h3>
    <p v-if="dossierFulltext.text">{{ dossierFulltext.text }}</p>
    <ol v-else>
      <li v-for="item in dossierFulltext.items" :key="item">{{ item }}</li>
    </ol>
    <button
      type="button"
      data-dismiss-dossier-fulltext
      @click="closeDossierFulltext"
    >
      关闭
    </button>
  </section>
</div>
```

- [ ] **Step 5: Add dialog styles that cannot overflow mobile viewports**

```css
.intake-fulltext-dialog {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 16px;
  background: #25354a66;
  backdrop-filter: blur(8px);
}
.intake-fulltext-dialog__card {
  display: grid;
  width: min(620px, calc(100dvw - 32px));
  max-height: min(680px, calc(100dvh - 32px));
  gap: 12px;
  padding: 20px;
  overflow-y: auto;
  overflow-wrap: anywhere;
  background: #fff;
  border-radius: 22px;
}
.intake-fulltext-dialog__card button {
  min-width: 88px;
  min-height: 44px;
  justify-self: end;
}
```

- [ ] **Step 6: Run the behavior tests and verify they pass**

```powershell
pnpm test -- src/components/common/ExpandableText.test.js src/views/disputes/IntakeRoomView.test.js
```

Expected: all focused tests PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend/src/components/common frontend/src/views/disputes/IntakeRoomView.vue frontend/src/views/disputes/IntakeRoomView.test.js
git commit -m "feat: preserve full intake dossier text"
```

## Task 5: Rebuild the intake shell geometry

- [ ] **Step 1: Add failing source-contract tests**

Update the final layout contract test in `IntakeRoomView.test.js` to require:

```js
expect(source).toContain("--intake-panel-height: 740px;");
expect(source).toContain("grid-template-rows: 60px minmax(0, 1fr) 52px;");
expect(source).toContain("grid-template-rows: 44px 412px 96px;");
expect(source).toContain("grid-template-columns: repeat(2, minmax(0, 1fr));");
expect(source).toContain("grid-template-columns: repeat(3, minmax(0, 1fr));");
expect(source).toContain("grid-template-columns: repeat(2, minmax(0, 1fr));");
expect(source).toContain("-webkit-line-clamp: 2;");
expect(source).toContain("@container room-workspace (min-width: 1060px)");
expect(source).not.toContain("@media (max-width: 980px)");
expect(source).not.toMatch(
  /@media \(max-width: 580px\)[\s\S]*?intake-dossier__actions--two-column[\s\S]*?grid-template-columns: 1fr/,
);
```

Add `frontend/src/styles.test.js`:

```js
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

describe("global responsive width", () => {
  it("does not impose a 320px minimum width on root scrolling elements", () => {
    const source = readFileSync("src/styles.css", "utf8");
    expect(source).not.toMatch(/html\s*\{[^}]*min-width:\s*320px/);
    expect(source).not.toMatch(/body\s*\{[^}]*min-width:\s*320px/);
  });
});
```

Update `RoomShell.test.js` to assert `.room-shell__workspace` defines `container: room-workspace / inline-size`.

- [ ] **Step 2: Run tests and verify they fail**

```powershell
pnpm test -- src/views/disputes/IntakeRoomView.test.js src/components/room/RoomShell.test.js src/styles.test.js
```

Expected: FAIL because the existing layout still uses the 980px media query, residual-height Grid rows, 580px stacked actions, and root `min-width: 320px`.

- [ ] **Step 3: Establish the RoomShell container without changing other pages**

In `RoomShell.vue` add:

```css
.room-shell__workspace {
  container: room-workspace / inline-size;
  min-width: 0;
}
```

The container declaration has no visual effect until a room consumes it.

- [ ] **Step 4: Remove the root minimum width**

In `frontend/src/styles.css`, replace:

```css
html { min-width: 320px; min-height: 100%; background: #f7fbff; }
```

with:

```css
html { min-height: 100%; background: #f7fbff; }
```

Remove `min-width: 320px;` from `body`. Do not add global `overflow-x: hidden`.

- [ ] **Step 5: Apply the exact 740px outer geometry**

In `IntakeRoomView.vue` use:

```css
.intake-room {
  --intake-panel-height: 740px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}
@container room-workspace (min-width: 1060px) {
  .intake-room {
    grid-template-columns: minmax(0, 1.05fr) minmax(0, .95fr);
  }
}
.intake-room__conversation,
.intake-dossier {
  box-sizing: border-box;
  height: var(--intake-panel-height);
  min-width: 0;
  overflow: hidden;
}
.intake-room__conversation {
  display: grid;
  grid-template-rows: 120px minmax(0, 1fr);
}
.intake-dossier {
  display: grid;
  grid-template-rows: 60px minmax(0, 1fr) 52px;
  gap: 8px;
  padding: 14px 18px;
}
.intake-case-detail {
  display: grid;
  grid-template-rows: 44px 412px 96px;
  gap: 8px;
  height: auto;
  min-height: 0;
  margin: 0;
}
```

Remove the existing `@media (max-width: 980px)` and `@media (max-width: 580px)` layout overrides for the intake grid/actions.

- [ ] **Step 6: Rebuild the 412px dispute card**

Use:

```css
.intake-case-detail__dispute {
  display: grid;
  height: 412px;
  grid-template-rows: 18px 110px 112px 108px;
  gap: 6px;
  padding: 12px 14px;
  overflow: hidden;
}
.intake-case-detail__summary-note {
  min-height: 0;
  height: 110px;
  padding: 12px 16px;
}
.intake-case-detail__meta-rows {
  display: grid;
  height: 112px;
  grid-template-rows: 70px 42px;
  min-height: 0;
}
.intake-case-detail__fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
}
.intake-case-detail__field {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 4px;
  min-width: 0;
  padding: 7px 10px;
}
.intake-case-detail__index-strip {
  height: 42px;
  grid-template-columns: 58px minmax(0, 1fr);
}
.intake-case-detail__index-list {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.intake-case-detail__origin-card {
  height: 108px;
  min-height: 108px;
  grid-template-rows: 18px minmax(0, 1fr);
  padding-top: 4px;
}
.intake-case-detail__single-statement {
  overflow: hidden;
}
```

At content widths below 420px, override only the preview budgets, not the column count:

```css
@container room-workspace (max-width: 419px) {
  .intake-case-detail__dispute {
    grid-template-rows: 18px 96px 126px 108px;
  }
  .intake-case-detail__summary-note { height: 96px; }
  .intake-case-detail__meta-rows {
    height: 126px;
    grid-template-rows: 84px 42px;
  }
  .intake-case-detail__origin-card { height: 108px; }
}
```

- [ ] **Step 7: Rebuild the 112px verification slot and 52px actions**

```css
.intake-case-detail__todo-list {
  display: grid;
  height: 112px;
  grid-template-rows: 20px minmax(0, 1fr);
  gap: 6px;
  padding: 8px 10px;
  overflow: hidden;
}
.intake-case-detail__todo-list ol {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 4px 8px;
  min-height: 0;
}
.intake-case-detail__todo-text {
  -webkit-line-clamp: 2;
  font-size: 11px;
  line-height: 1.3;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.intake-dossier__confirm,
.intake-dossier__actions,
.intake-dossier__actions--two-column {
  height: 52px;
  min-height: 52px;
}
.intake-dossier__actions--two-column {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.intake-dossier__confirm button {
  min-width: 0;
  min-height: 52px;
  padding: 7px 10px;
  white-space: normal;
}
```

Keep readonly and admitted states inside the same 52px bottom slot. Move decorative `.intake-dossier__stamp` out of normal Grid flow or render it inside the primary button state.

Replace the current trailing stamp/resolved paragraphs with one controlled bottom-slot branch:

```vue
<div
  v-else-if="resolved"
  class="intake-dossier__result"
  data-intake-result
>
  争议已取消，接待室已归档
</div>
```

The admitted state continues to use the two action buttons, with the primary button text `已上报`; remove `.intake-dossier__stamp`. Shorten readonly copy inside the 52px slot to `当前身份仅可查看接待室卷宗` and preserve the longer explanation in `title` or accessible description.

- [ ] **Step 8: Run focused tests and make them pass**

```powershell
pnpm test -- src/components/common/ExpandableText.test.js src/components/room/RoomShell.test.js src/components/room/ConversationStream.test.js src/views/disputes/IntakeRoomView.test.js src/styles.test.js
```

Expected: all focused tests PASS.

- [ ] **Step 9: Run the browser layout test and iterate only on intake CSS until it passes**

```powershell
pnpm test:browser:intake
```

Expected: all intake browser layout tests PASS. Do not weaken geometry assertions to accommodate clipping.

- [ ] **Step 10: Commit the intake geometry**

```powershell
git add frontend/src/components/room/RoomShell.vue frontend/src/components/room/RoomShell.test.js frontend/src/views/disputes/IntakeRoomView.vue frontend/src/views/disputes/IntakeRoomView.test.js frontend/src/styles.css frontend/src/styles.test.js
git commit -m "fix: stabilize intake room layout"
```

## Task 6: Full verification, push, and user stop gate

- [ ] **Step 1: Run the complete frontend verification**

```powershell
pnpm test
pnpm build
pnpm test:browser
```

Expected: Vitest reports zero failures, Vite build exits 0, Playwright Chromium reports zero failures.

- [ ] **Step 2: Check the final diff is intake-scoped**

```powershell
Set-Location ..
git status --short
git diff main...HEAD --stat
git diff main...HEAD -- frontend/src/views/disputes/EvidenceRoomView.vue frontend/src/views/disputes/HearingCourtView.vue
```

Expected: no diff for EvidenceRoomView or HearingCourtView. Shared changes are limited to RoomShell container metadata, ConversationStream contract, `ExpandableText`, browser-test infrastructure, and root minimum width.

- [ ] **Step 3: Run live dev services and select a real intake case**

Keep the existing Java API on 8080 and start frontend dev mode from the worktree:

```powershell
Set-Location frontend
pnpm dev
```

In another PowerShell session:

```powershell
$headers=@{'X-User-Id'='user-local';'X-Role'='USER'}
$items=(Invoke-RestMethod 'http://127.0.0.1:8080/api/disputes?page=0&size=100' -Headers $headers).data.items
$case=$items | Where-Object { $_.current_room -eq 'INTAKE' } | Select-Object -First 1
$case.id
```

Expected: one real `CASE_*` ID. If no intake case exists, use the existing external-import workflow to create one; do not hard-code a stale case ID into source or tests.

Exact fallback command:

```powershell
$key="intake-layout-$(New-Guid)"
$body=@{
  count=1
  scenario='接待室固定外框长文本验收'
  risk_level_hint='MEDIUM'
  initiator_role_hint='USER'
  current_actor_id='user-local'
  counterparty_actor_id='merchant-local'
  simulation_batch_id=$key
} | ConvertTo-Json
$created=Invoke-RestMethod `
  'http://127.0.0.1:8080/api/disputes/import/simulate' `
  -Method Post `
  -Headers @{
    'X-User-Id'='user-local'
    'X-Role'='USER'
    'Idempotency-Key'=$key
  } `
  -ContentType 'application/json' `
  -Body $body
$caseId=$created.data.items[0].case_id
$caseId
```

Expected: exactly one imported case ID.

- [ ] **Step 4: Perform manual browser acceptance**

Generate and open the exact URL:

```powershell
$url="http://127.0.0.1:5173/disputes/$caseId/intake"
$url
```

Check at normal desktop width and compact width:

1. Both main cards remain exactly 740px high.
2. Chat history scrolls while the composer stays visible.
3. Summary, claim/response, three indexes, origin preview and four verification items remain inside the dossier.
4. Full-text buttons open complete content in a viewport-safe dialog.
5. Both intake actions stay on one row at 580px and 320px.
6. No page-level horizontal scrollbar appears.

- [ ] **Step 5: Push the room branch**

```powershell
Set-Location ..
git push -u origin codex/layout-intake-room
```

Expected: remote branch is created successfully.

- [ ] **Step 6: Stop and hand the room to the user**

Report only:

- Intake changes made.
- Fixed 740px shell confirmation.
- The exact allowed scroll regions.
- Vitest/build/Playwright results.
- Branch and commit hashes.
- Current browser acceptance URL.
- Five manual observations listed above.

Do not merge `main`, do not start Evidence Room, and do not modify another page until the user explicitly confirms the intake room.
