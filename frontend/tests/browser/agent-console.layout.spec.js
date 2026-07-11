import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task8-agent-console/",
    import.meta.url,
  ),
);

const reviewer = {
  id: "reviewer-local",
  role: "PLATFORM_REVIEWER",
  label: "骞冲彴瀹℃牳鍛?",
};

const longToken = "AGENT_CONFIG_LONG_UNBROKEN_TOKEN_".repeat(34);

const viewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

async function installAgentConsoleFixture(page) {
  await page.addInitScript((value) => {
    localStorage.setItem("dispute-actor", JSON.stringify(value));
  }, reviewer);

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(request.headers()).toMatchObject({
      "x-user-id": reviewer.id,
      "x-role": reviewer.role,
    });

    if (request.method() === "GET" && url.pathname === "/api/notifications") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: [] }),
      });
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: { unread_count: 0 } }),
      });
    }

    throw new Error(
      `Unhandled agent-console browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}

async function openAgentConsole(page, viewport) {
  await page.setViewportSize(viewport);
  await installAgentConsoleFixture(page);
  await page.goto("/agents");
  await expect(page.locator("[data-agent-console]")).toBeVisible();
}

async function injectLongAgentCopy(page) {
  await page.evaluate((token) => {
    for (const selector of [
      ".agent-console__intro p",
      ".agent-console__intro > strong",
      ".agent-ticket small",
      ".agent-ticket i",
      ".agent-panel__header p",
      ".agent-config-tabs small",
      ".jury-strategy__node",
      ".jury-strategy p",
      ".prompt-card dd",
      ".prompt-card p",
      ".skill-badges span",
      ".court-preview b",
      ".court-mapping p",
    ]) {
      for (const element of document.querySelectorAll(selector)) {
        element.textContent = `${element.textContent} ${token}`;
      }
    }
  }, longToken);
}

async function assertNoPageHorizontalOverflow(page) {
  const report = await page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    const offenders = [...document.querySelectorAll("body *")]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          tag: element.tagName.toLowerCase(),
          className:
            typeof element.className === "string" ? element.className : "",
          left: Math.round(rect.left * 10) / 10,
          right: Math.round(rect.right * 10) / 10,
          width: Math.round(rect.width * 10) / 10,
        };
      })
      .filter(({ left, right }) => left < -1 || right > viewportWidth + 1)
      .slice(0, 20);
    return {
      viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders,
    };
  });
  expect(
    report.scrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

async function captureLayoutScreenshot(page, { viewport, scenario }) {
  if (process.env.CAPTURE_LAYOUT_SCREENSHOTS !== "1") return;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.screenshot({
    path: path.join(
      screenshotDirectory,
      `task8-agent-console-${viewport.width}x${viewport.height}-${scenario}.png`,
    ),
    fullPage: true,
  });
}

for (const viewport of viewports) {
  test(`keeps agent console contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openAgentConsole(page, viewport);

    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, { viewport, scenario: "normal" });
  });

  test(`keeps future long agent config copy contained at ${viewport.width}px`, async ({
    page,
  }) => {
    await openAgentConsole(page, viewport);
    await injectLongAgentCopy(page);

    await assertNoPageHorizontalOverflow(page);
    await captureLayoutScreenshot(page, { viewport, scenario: "long" });
  });
}
