// 文件作用：自动化测试文件，验证 evidence-room.layout.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect, test } from "@playwright/test";
import {
  CASE_ID,
  LONG_FILENAME,
  LONG_UNBROKEN_TEXT,
  installEvidenceRoomFixture,
} from "./fixtures/evidence-room.fixture.js";

// 业务位置：【前端浏览器回归测试】gridTrackCount：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
function gridTrackCount(value) {
  return value.trim().split(/\s+/).filter(Boolean).length;
}

// 业务位置：【前端浏览器回归测试】assertInside：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
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

// 业务位置：【前端浏览器回归测试】horizontalOverflowReport：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
    const viewportWidth = document.documentElement.clientWidth;
    return {
      clientWidth: viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders: [...document.querySelectorAll("body *")]
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
        .sort((left, right) => right.right - left.right)
        .slice(0, 12),
    };
  });
}

// 业务位置：【前端浏览器回归测试】assertNoDocumentHorizontalOverflow：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertNoDocumentHorizontalOverflow(page) {
  const report = await horizontalOverflowReport(page);
  expect(report.scrollWidth, JSON.stringify(report, null, 2)).toBeLessThanOrEqual(
    report.clientWidth + 1,
  );
}

// 业务位置：【前端浏览器回归测试】assertTouchHeight：核验 当前阶段业务数据 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertTouchHeight(locator) {
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  expect(box.height).toBeGreaterThanOrEqual(44);
}

// 业务位置：【前端浏览器回归测试】openEvidenceRoom：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function openEvidenceRoom(page, options = {}) {
  await installEvidenceRoomFixture(page, options);
  await page.goto(`/disputes/${CASE_ID}/evidence`);
  await expect(page.locator("[data-evidence-board-panel]")).toBeVisible();
}

// 业务位置：【前端浏览器回归测试】assertEvidenceGeometry：核验 当前可见证据和附件 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function assertEvidenceGeometry(page, expectedColumns) {
  const room = page.locator("[data-evidence-room-layout]");
  const chat = page.locator("[data-evidence-chat-panel]");
  const board = page.locator("[data-evidence-board-panel]");
  const list = page.locator("[data-evidence-list-scroll]");
  const uploader = page.locator(".evidence-uploader");
  const uploadButton = uploader.locator(".evidence-uploader__button");
  const footer = page.locator(".evidence-footer");
  const completeButton = page.locator("[data-complete-evidence]");

  await expect(chat).toHaveCSS("height", "740px");
  await expect(board).toHaveCSS("height", "740px");
  expect(
    gridTrackCount(
      await room.evaluate(
        (element) => getComputedStyle(element).gridTemplateColumns,
      ),
    ),
  ).toBe(expectedColumns);
  await expect(list).toBeVisible();
  await expect(uploader).toBeVisible();
  await expect(uploadButton).toBeVisible();
  await expect(completeButton).toBeVisible();
  await assertInside(list, board);
  await assertInside(uploader, board);
  await assertInside(uploadButton, uploader);
  await assertInside(footer, board);

  const scrollingRegions = await board.evaluate((element) =>
    [...element.querySelectorAll("*")]
      .filter((candidate) => {
        const style = getComputedStyle(candidate);
        const scrollsVertically =
          ["auto", "scroll"].includes(style.overflowY) &&
          candidate.scrollHeight > candidate.clientHeight + 1;
        const scrollsHorizontally =
          ["auto", "scroll"].includes(style.overflowX) &&
          candidate.scrollWidth > candidate.clientWidth + 1;
        return scrollsVertically || scrollsHorizontally;
      })
      .map((candidate) => candidate.className),
  );
  expect(scrollingRegions.length).toBeGreaterThan(0);
  expect(
    scrollingRegions.every((className) =>
      String(className).includes("evidence-card-strip"),
    ),
  ).toBe(true);
  await assertNoDocumentHorizontalOverflow(page);
}

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("renders upload attestation and human-review cards for visual evidence", async ({
  page,
}) => {
  await openEvidenceRoom(page, { role: "USER", count: 6 });

  await page.locator("[data-open-evidence-upload]").click();
  const uploadDeclaration = page.locator("[data-evidence-upload-modal]");
  await expect(uploadDeclaration).toContainText("真实性责任告知");
  await expect(uploadDeclaration).toContainText("真实性与相关性承诺");
  await page.locator("[data-cancel-evidence-upload]").click();
  const queue = page.locator("[data-human-review-queue]");
  await expect(queue).toBeVisible();
  await expect(queue.locator("[data-human-review-card]")).toHaveCount(2);
  await expect(queue).toContainText("人工审核任务");
  await queue.scrollIntoViewIfNeeded();
  const reviewCard = queue.locator("[data-human-review-card]").first();
  await reviewCard.scrollIntoViewIfNeeded();
  await reviewCard.click();
  const reviewDetail = page.locator("[data-evidence-detail-modal]");
  await expect(reviewDetail.locator("[data-evidence-detail-assessment]")).toContainText(
    "真实性78%",
  );
  await expect(reviewDetail.locator("[data-evidence-detail-assessment]")).toContainText(
    "核验把握67%",
  );
  await expect(reviewDetail.locator("[data-evidence-detail-human-review]")).toContainText(
    "细微外观损伤需要人工查看原图",
  );
  await reviewDetail.locator("[data-close-evidence-modal]").click();

  await page.screenshot({
    path: "test-results/evidence-human-review.png",
    fullPage: false,
  });
});

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("switches columns at 1060/1059px of actual workspace width", async ({
  page,
}) => {
  await openEvidenceRoom(page, { role: "USER", count: 100 });
  const workspace = page.locator(".room-shell__workspace");

  await page.addStyleTag({ content: ".app-page{width:1060px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBe(1060);
  await assertEvidenceGeometry(page, 2);

  await page.addStyleTag({ content: ".app-page{width:1059px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBe(1059);
  await assertEvidenceGeometry(page, 1);
});

for (const [index, viewport] of [
  { width: 1121, height: 900 },
  { width: 1120, height: 900 },
  { width: 1061, height: 900 },
  { width: 1060, height: 900 },
  { width: 981, height: 900 },
  { width: 980, height: 900 },
  { width: 581, height: 843 },
  { width: 580, height: 843 },
  { width: 390, height: 844 },
  { width: 320, height: 568 },
  { width: 1024, height: 600 },
].entries()) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps fixed evidence geometry and actions at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await openEvidenceRoom(page, {
      role: index % 2 === 0 ? "USER" : "MERCHANT",
      count: 100,
    });

    const workspaceWidth = await page
      .locator(".room-shell__workspace")
      .evaluate((element) => element.clientWidth);
    await assertEvidenceGeometry(page, workspaceWidth >= 1060 ? 2 : 1);

    if (viewport.height === 600) {
      expect(
        await page.evaluate(
          () =>
            document.documentElement.scrollHeight >
            document.documentElement.clientHeight,
        ),
      ).toBe(true);
    }
  });
}
for (const role of ["USER", "MERCHANT"]) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`keeps 100 ${role} evidence cards in bounded board rails`, async ({
    page,
  }) => {
    await openEvidenceRoom(page, { role, count: 100 });

    const chat = page.locator("[data-evidence-chat-panel]");
    const board = page.locator("[data-evidence-board-panel]");
    const list = page.locator("[data-evidence-list-scroll]");
    const footer = page.locator(".evidence-footer");

    await expect(chat).toHaveCSS("height", "740px");
    await expect(board).toHaveCSS("height", "740px");
    const submittedStrip = list.locator(
      ".evidence-library--private [data-evidence-horizontal-strip]",
    );
    const humanReviewStrip = list.locator("[data-human-review-list]");
    await expect(
      list.locator(".evidence-library--private [data-evidence-card]"),
    ).toHaveCount(100);
    await expect(humanReviewStrip.locator("[data-human-review-card]")).toHaveCount(
      34,
    );
    expect(
      await submittedStrip.evaluate(
        (element) => element.scrollWidth > element.clientWidth + 1,
      ),
    ).toBe(true);
    expect(
      await humanReviewStrip.evaluate(
        (element) => element.scrollHeight > element.clientHeight + 1,
      ),
    ).toBe(true);
    expect(await list.locator(".evidence-footer").count()).toBe(0);
    await assertInside(list, board);
    await assertInside(footer, board);

    const scrollingRegions = await board.evaluate((element) =>
      [...element.querySelectorAll("*")]
        .filter((candidate) => {
          const style = getComputedStyle(candidate);
          const scrollsVertically =
            ["auto", "scroll"].includes(style.overflowY) &&
            candidate.scrollHeight > candidate.clientHeight + 1;
          const scrollsHorizontally =
            ["auto", "scroll"].includes(style.overflowX) &&
            candidate.scrollWidth > candidate.clientWidth + 1;
          return scrollsVertically || scrollsHorizontally;
        })
        .map((candidate) => candidate.className),
    );
    expect(scrollingRegions.length).toBeGreaterThanOrEqual(2);
    expect(
      scrollingRegions.every((className) =>
        String(className).includes("evidence-card-strip"),
      ),
    ).toBe(true);
  });
}

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("keeps the compact uploader and footer horizontal at 620/619px", async ({
  page,
}) => {
  await page.setViewportSize({ width: 620, height: 900 });
  await openEvidenceRoom(page, { role: "MERCHANT", count: 12 });

  for (const width of [620, 619]) {
    await page.setViewportSize({ width, height: 900 });
    const board = page.locator("[data-evidence-board-panel]");
    const uploader = page.locator(".evidence-uploader");
    const footer = page.locator(".evidence-footer");

    await expect(page.locator(".evidence-uploader__illustration")).toBeHidden();
    await expect(page.locator(".evidence-uploader .evidence-kicker")).toBeHidden();
    expect(
      gridTrackCount(
        await uploader.evaluate(
          (element) => getComputedStyle(element).gridTemplateColumns,
        ),
      ),
    ).toBe(2);
    expect(
      gridTrackCount(
        await footer.evaluate(
          (element) => getComputedStyle(element).gridTemplateColumns,
        ),
      ),
    ).toBe(2);
    expect(
      await uploader.evaluate(
        (element) => element.scrollHeight <= element.clientHeight + 1,
      ),
    ).toBe(true);
    expect(
      await footer.evaluate(
        (element) => element.scrollHeight <= element.clientHeight + 1,
      ),
    ).toBe(true);
    await assertInside(uploader, board);
    await assertInside(footer, board);
    await assertNoDocumentHorizontalOverflow(page);
  }
});

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("keeps uploader and completion controls at least 44px tall without changing the 740px board", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await openEvidenceRoom(page, { role: "USER", count: 4 });

  const board = page.locator("[data-evidence-board-panel]");
  await expect(board).toHaveCSS("height", "740px");
  await assertTouchHeight(page.locator(".evidence-uploader__button"));
  await assertTouchHeight(page.locator("[data-complete-evidence]"));
  await assertInside(page.locator(".evidence-footer"), board);
});

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("keeps the hearing entrance at least 44px tall inside the sealed footer", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await openEvidenceRoom(page, {
    role: "USER",
    count: 4,
    sealed: true,
  });

  const board = page.locator("[data-evidence-board-panel]");
  const enterHearing = page.locator("[data-enter-hearing]");
  await expect(board).toHaveCSS("height", "740px");
  await expect(enterHearing).toBeVisible();
  await assertTouchHeight(enterHearing);
  await assertInside(page.locator(".evidence-footer"), board);
});

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("traps the evidence gate focus and restores the completion trigger on Escape", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openEvidenceRoom(page, {
    role: "USER",
    count: 0,
    initiatorRole: "USER",
  });

  const completionTrigger = page.locator("[data-complete-evidence]");
  await completionTrigger.focus();
  await completionTrigger.click();

  const modal = page.locator("[data-evidence-gate-modal]");
  const dismiss = modal.locator("[data-dismiss-evidence-gate]");
  await expect(modal).toBeVisible();
  await expect(dismiss).toBeFocused();
  await assertTouchHeight(dismiss);

  await page.keyboard.press("Tab");
  await expect(dismiss).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(dismiss).toBeFocused();
  await page.keyboard.press("Escape");

  await expect(modal).toBeHidden();
  await expect(completionTrigger).toBeFocused();
});

// 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
test("keeps evidence detail focus contained and restores its card", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openEvidenceRoom(page, { role: "USER", count: 4 });

  const evidenceCard = page
    .locator(".evidence-library--private [data-evidence-card]")
    .first();
  await evidenceCard.focus();
  await evidenceCard.click();

  const detail = page.locator("[data-evidence-detail-modal]");
  const detailClose = detail.locator("[data-close-evidence-modal]");
  await expect(detail).toBeVisible();
  await expect(detailClose).toBeFocused();
  await assertTouchHeight(detailClose);
  await page.keyboard.press("Tab");
  await expect(detailClose).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(detailClose).toBeFocused();

  await page.keyboard.press("Escape");
  await expect(detail).toBeHidden();
  await expect(evidenceCard).toBeFocused();
});

for (const scenario of [
  { width: 390, height: 844, role: "USER" },
  { width: 320, height: 568, role: "MERCHANT" },
]) {
  // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
  test(`contains long evidence text and its detail modal at ${scenario.width}px for ${scenario.role}`, async ({
    page,
  }) => {
    await page.setViewportSize({
      width: scenario.width,
      height: scenario.height,
    });
    await openEvidenceRoom(page, { role: scenario.role, count: 100 });

    await expect(page.locator(".evidence-board__badge")).toContainText(
      scenario.role === "USER" ? "用户证据方" : "商家证据方",
    );
    await assertNoDocumentHorizontalOverflow(page);

    const firstCard = page
      .locator(".evidence-library--private [data-evidence-card]")
      .first();
    const filename = firstCard.locator("[data-evidence-filename]");
    await expect(filename).toHaveAttribute("title", LONG_FILENAME);

    await firstCard.click();
    const modal = page.locator("[data-evidence-detail-modal]");
    const panel = modal.locator(".evidence-modal__panel");
    const longParagraphs = modal.locator(".evidence-modal__feedback p");
    const filenameHeading = modal.locator(".evidence-modal__identity h2");
    await expect(modal).toBeVisible();
    await expect(filenameHeading).toContainText(LONG_FILENAME);
    await expect(longParagraphs).toHaveCount(1);
    await expect(longParagraphs.nth(0)).toContainText(LONG_UNBROKEN_TEXT);
    expect(
      await panel.evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true);
    expect(
      await filenameHeading.evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true);
    expect(
      await modal.evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true);
    for (const paragraph of await longParagraphs.all()) {
      expect(
        await paragraph.evaluate(
          (element) => element.scrollWidth <= element.clientWidth + 1,
        ),
      ).toBe(true);
    }
    await assertNoDocumentHorizontalOverflow(page);
  });
}
