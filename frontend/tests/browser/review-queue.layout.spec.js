import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task6-review-queue/",
    import.meta.url,
  ),
);

const reviewer = {
  id: "reviewer-local",
  role: "PLATFORM_REVIEWER",
  label: "骞冲彴瀹℃牳鍛?",
};

const longToken = "REVIEW_QUEUE_LONG_TOKEN_".repeat(28);

const viewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

function fulfillJson(route, data) {
  return route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ success: true, data }),
  });
}

async function installReviewQueueFixture(page, scenario = "normal") {
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
      return fulfillJson(route, []);
    }
    if (
      request.method() === "GET" &&
      url.pathname === "/api/notifications/unread-count"
    ) {
      return fulfillJson(route, { unread_count: 0 });
    }
    if (request.method() === "GET" && url.pathname === "/api/reviews") {
      return fulfillJson(route, [
        {
          id:
            scenario === "long"
              ? `REVIEW_${longToken}`
              : "REVIEW_QUEUE_LAYOUT_1",
          case_id:
            scenario === "long"
              ? `CASE_${longToken}`
              : "CASE_QUEUE_LAYOUT_1",
          status: "PENDING",
          priority: "URGENT",
          required_role: "PLATFORM_REVIEWER",
          due_at: "2026-07-11T18:00:00+08:00",
        },
        {
          id: "REVIEW_QUEUE_LAYOUT_2",
          case_id: "CASE_QUEUE_LAYOUT_2",
          status: "IN_PROGRESS",
          priority: "MEDIUM",
          required_role: "PLATFORM_REVIEWER",
          due_at: null,
        },
      ]);
    }

    throw new Error(
      `Unhandled review-queue browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}

async function openReviewQueue(page, { viewport, scenario = "normal" }) {
  await page.setViewportSize(viewport);
  await installReviewQueueFixture(page, scenario);
  await page.goto("/reviews");
  await expect(page.locator("[data-review-task]")).toHaveCount(2);
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
      .slice(0, 16);
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
      `task6-review-queue-${viewport.width}x${viewport.height}-${scenario}.png`,
    ),
    fullPage: true,
  });
}

for (const viewport of viewports) {
  for (const scenario of ["normal", "long"]) {
    test(`keeps review queue contained at ${viewport.width}px with ${scenario} data`, async ({
      page,
    }) => {
      await openReviewQueue(page, { viewport, scenario });

      await assertNoPageHorizontalOverflow(page);

      if (scenario === "long") {
        const caseId = page.locator("[data-review-case-id]").first();
        await expect(caseId).toHaveAttribute("title", `CASE_${longToken}`);
        const metrics = await caseId.evaluate((element) => {
          const style = getComputedStyle(element);
          return {
            scrollWidth: element.scrollWidth,
            clientWidth: element.clientWidth,
            overflow: style.overflow,
            textOverflow: style.textOverflow,
            whiteSpace: style.whiteSpace,
          };
        });
        expect(metrics).toMatchObject({
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
        });
        await expect(caseId).not.toHaveText(`CASE_${longToken}`);
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1);
      }

      await captureLayoutScreenshot(page, { viewport, scenario });
    });
  }
}
