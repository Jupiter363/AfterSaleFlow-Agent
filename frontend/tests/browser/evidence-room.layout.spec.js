import { expect, test } from "@playwright/test";
import {
  CASE_ID,
  LONG_FILENAME,
  installEvidenceRoomFixture,
} from "./fixtures/evidence-room.fixture.js";

function gridTrackCount(value) {
  return value.trim().split(/\s+/).filter(Boolean).length;
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

async function assertNoDocumentHorizontalOverflow(page) {
  const report = await horizontalOverflowReport(page);
  expect(report.scrollWidth, JSON.stringify(report, null, 2)).toBeLessThanOrEqual(
    report.clientWidth + 1,
  );
}

async function openEvidenceRoom(page, options = {}) {
  await installEvidenceRoomFixture(page, options);
  await page.goto(`/disputes/${CASE_ID}/evidence`);
  await expect(page.locator("[data-evidence-board-panel]")).toBeVisible();
}

test("switches columns at 1120/1119px of actual workspace width", async ({
  page,
}) => {
  await openEvidenceRoom(page, { role: "USER", count: 8 });
  const workspace = page.locator(".room-shell__workspace");
  const room = page.locator("[data-evidence-room-layout]");

  await page.addStyleTag({ content: ".app-page{width:1120px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBe(1120);
  expect(
    gridTrackCount(
      await room.evaluate(
        (element) => getComputedStyle(element).gridTemplateColumns,
      ),
    ),
  ).toBe(2);

  await page.addStyleTag({ content: ".app-page{width:1119px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBe(1119);
  expect(
    gridTrackCount(
      await room.evaluate(
        (element) => getComputedStyle(element).gridTemplateColumns,
      ),
    ),
  ).toBe(1);
  await assertNoDocumentHorizontalOverflow(page);
});

for (const role of ["USER", "MERCHANT"]) {
  test(`keeps 100 ${role} evidence cards in the sole right-board scroll rail`, async ({
    page,
  }) => {
    await openEvidenceRoom(page, { role, count: 100 });

    const chat = page.locator("[data-evidence-chat-panel]");
    const board = page.locator("[data-evidence-board-panel]");
    const list = page.locator("[data-evidence-list-scroll]");
    const footer = page.locator(".evidence-footer");

    await expect(chat).toHaveCSS("height", "740px");
    await expect(board).toHaveCSS("height", "740px");
    await expect(list.locator("[data-evidence-card]")).toHaveCount(100);
    expect(
      await list.evaluate(
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
          return (
            ["auto", "scroll"].includes(style.overflowY) &&
            candidate.scrollHeight > candidate.clientHeight + 1
          );
        })
        .map((candidate) => candidate.className),
    );
    expect(scrollingRegions).toEqual(["evidence-board__list"]);
  });
}

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

for (const scenario of [
  { width: 390, height: 844, role: "USER" },
  { width: 320, height: 568, role: "MERCHANT" },
]) {
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
    const description = firstCard.locator("[data-evidence-description]");
    await expect(filename).toHaveAttribute("title", LONG_FILENAME);
    expect((await description.textContent()).trim().length).toBeGreaterThanOrEqual(
      200,
    );

    await firstCard.click();
    const modal = page.locator("[data-evidence-detail-modal]");
    const panel = modal.locator(".evidence-modal__panel");
    const filenameFact = modal
      .locator(".evidence-modal__facts span")
      .filter({ hasText: "原始文件：" });
    await expect(modal).toBeVisible();
    await expect(filenameFact).toContainText(LONG_FILENAME);
    expect(
      await panel.evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true);
    expect(
      await filenameFact.evaluate(
        (element) => element.scrollWidth <= element.clientWidth + 1,
      ),
    ).toBe(true);
    await assertNoDocumentHorizontalOverflow(page);
  });
}
