import { expect, test } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const REVIEW_ID = "REVIEW_WORKBENCH_LAYOUT";
const screenshotDirectory = fileURLToPath(
  new URL(
    "../../../.codex-run/layout-validation/task7-review-workbench/",
    import.meta.url,
  ),
);

const reviewer = {
  id: "reviewer-local",
  role: "PLATFORM_REVIEWER",
  label: "骞冲彴瀹℃牳鍛?",
};

const longToken = "REVIEW_PACKET_LONG_UNBROKEN_TOKEN_".repeat(32);
const longParagraph =
  "ReviewPacket 鍐荤粨鍖呭惈妗堜欢鎽樿銆佸弻鏂逛富寮犮€佽瘉鎹煩闃点€佽崏妗堝缓璁拰鎵ц鏂规锛屽鏍稿憳闇€瑕佸湪鍗曚竴宸ヤ綔鍙板唴瀹屾垚澶嶆牳銆?".repeat(
    8,
  );

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

function packet(scenario = "normal") {
  const pressure = scenario === "long";
  return {
    id: "PACKET_WORKBENCH_LAYOUT",
    case_id: pressure ? `CASE_${longToken}` : "CASE_WORKBENCH_LAYOUT",
    packet_version: pressure ? 987654321 : 3,
    dossier_version: pressure ? 123456 : 2,
    ruleset_version: pressure ? `RULESET_${longToken}` : "rules-2026.07",
    frozen_at: "2026-07-11T10:00:00+08:00",
    expires_at: "2026-07-11T18:00:00+08:00",
    case_summary: {
      title: pressure ? `${longParagraph}${longToken}` : "绛炬敹鏈敹鍒颁簤璁?",
      risk_level: pressure ? `RISK_${longToken}` : "HIGH",
    },
    claims: {
      user: pressure ? `${longParagraph}${longToken}` : "鐢ㄦ埛绉版湭鏀跺埌鍟嗗搧",
      merchant: pressure ? `${longParagraph}${longToken}` : "鍟嗗绉扮墿娴佸凡绛炬敹",
    },
    issues: pressure
      ? [`${longParagraph}${longToken}`, `${longParagraph}${longToken}`]
      : ["绛炬敹浜鸿韩浠芥槸鍚﹀彲淇?", "鏄惁婊¤冻閫€娆炬潯浠?"],
    evidence_matrix: [
      {
        issue: pressure ? `${longParagraph}${longToken}` : "绛炬敹浜鸿韩浠?",
        supporting: pressure
          ? [`EVIDENCE_${longToken}`, `LOGISTICS_${longToken}`]
          : ["EVIDENCE_1"],
        conclusion: pressure ? `${longParagraph}${longToken}` : "闇€瑕佸鏍?",
      },
    ],
    draft: {
      recommended_decision: pressure ? `${longParagraph}${longToken}` : "寤鸿閫€娆?",
      draft_text: pressure ? `${longParagraph}${longToken}` : "寤鸿鏍搁獙绛炬敹鍑瘉",
      reviewer_attention: pressure
        ? [`ATTENTION_${longToken}`, `${longParagraph}${longToken}`]
        : ["鏍稿疄浠ｇ鍏崇郴"],
    },
    remedy: {
      actions: [
        {
          action_type: pressure ? `ACTION_${longToken}` : "REFUND",
          amount: 299,
          target: pressure ? `TARGET_${longToken}` : "USER",
          parameters: pressure ? { trace: longToken } : { currency: "CNY" },
          note: pressure ? `${longParagraph}${longToken}` : "寰呭鏍稿憳纭",
        },
      ],
    },
    risk_flags: pressure
      ? [`FLAG_${longToken}`, `${longParagraph}${longToken}`]
      : ["HIGH_VALUE", "SIGNATURE_MISMATCH"],
    status: "FROZEN",
  };
}

async function installWorkbenchFixture(page, scenario = "normal") {
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
    if (
      request.method() === "GET" &&
      url.pathname === `/api/reviews/${REVIEW_ID}/packet`
    ) {
      return fulfillJson(route, packet(scenario));
    }

    throw new Error(
      `Unhandled review-workbench browser-test API request: ${request.method()} ${url.pathname}${url.search}`,
    );
  });
}

async function openWorkbench(page, { viewport, scenario = "normal" }) {
  await page.setViewportSize(viewport);
  await installWorkbenchFixture(page, scenario);
  await page.goto(`/reviews/${REVIEW_ID}`);
  await expect(page.locator("[data-packet-status]")).toBeVisible();
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
      `task7-review-workbench-${viewport.width}x${viewport.height}-${scenario}.png`,
    ),
    fullPage: true,
  });
}

for (const viewport of viewports) {
  for (const scenario of ["normal", "long"]) {
    test(`keeps review workbench contained at ${viewport.width}px with ${scenario} data`, async ({
      page,
    }) => {
      await openWorkbench(page, { viewport, scenario });

      await assertNoPageHorizontalOverflow(page);
      await captureLayoutScreenshot(page, { viewport, scenario });
    });
  }
}
