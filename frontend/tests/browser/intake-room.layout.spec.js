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

async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
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
      .filter(
        ({ left, right }) => left < -1 || right > viewportWidth + 1,
      )
      .sort((left, right) => right.right - left.right)
      .slice(0, 12);
    const landmarks = [
      "html",
      "body",
      ".app-shell",
      ".app-header",
      ".app-page",
      ".room-shell",
      ".room-shell__header",
      ".room-shell__boundary",
      ".room-shell__workspace",
    ].map((selector) => {
      const element = document.querySelector(selector);
      const rect = element?.getBoundingClientRect();
      const style = element ? getComputedStyle(element) : null;
      return {
        selector,
        left: rect ? Math.round(rect.left * 10) / 10 : null,
        right: rect ? Math.round(rect.right * 10) / 10 : null,
        width: rect ? Math.round(rect.width * 10) / 10 : null,
        clientWidth: element?.clientWidth ?? null,
        scrollWidth: element?.scrollWidth ?? null,
        computedWidth: style?.width ?? null,
        minWidth: style?.minWidth ?? null,
        maxWidth: style?.maxWidth ?? null,
        boxSizing: style?.boxSizing ?? null,
      };
    });
    return {
      viewportWidth,
      scrollWidth: document.documentElement.scrollWidth,
      landmarks,
      offenders,
    };
  });
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

test("keeps 740px shells and switches by the 1060px workspace contract", async ({
  page,
}) => {
  await installIntakeRoomFixture(page);
  await page.goto(`/disputes/${CASE_ID}/intake`);
  const workspace = page.locator(".room-shell__workspace");
  const room = page.locator(".intake-room");
  const conversation = page.locator(".intake-room__conversation");
  const dossier = page.locator(".intake-dossier");

  await expect(conversation).toHaveCSS("height", "740px");
  await expect(dossier).toHaveCSS("height", "740px");

  await page.addStyleTag({ content: ".app-page{width:1060px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBeGreaterThanOrEqual(1060);
  const desktopColumns = await room.evaluate(
    (element) => getComputedStyle(element).gridTemplateColumns,
  );
  expect(desktopColumns.split(" ")).toHaveLength(2);

  await page.addStyleTag({ content: ".app-page{width:1059px!important}" });
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBeLessThan(1060);
  const compactColumns = await room.evaluate(
    (element) => getComputedStyle(element).gridTemplateColumns,
  );
  expect(compactColumns.split(" ")).toHaveLength(1);
  expect(await pageHasHorizontalOverflow(page)).toBe(false);
});

for (const viewport of [
  { width: 1121, height: 900 },
  { width: 1120, height: 900 },
  { width: 1061, height: 900 },
  { width: 1060, height: 900 },
  { width: 981, height: 900 },
  { width: 980, height: 900 },
  { width: 621, height: 843 },
  { width: 620, height: 843 },
  { width: 1024, height: 600 },
]) {
  test(`keeps the intake contract at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await installIntakeRoomFixture(page, {
      dossier: { summaryLength: 150, statementLength: 160, gapCount: 4 },
    });
    await page.goto(`/disputes/${CASE_ID}/intake`);

    const workspace = page.locator(".room-shell__workspace");
    const room = page.locator(".intake-room");
    const conversation = page.locator(".intake-room__conversation");
    const dossier = page.locator(".intake-dossier");
    const origin = page.locator("[data-origin-statement-card]");
    const actions = page.locator(".intake-dossier__actions--two-column");

    await expect(conversation).toHaveCSS("height", "740px");
    await expect(dossier).toHaveCSS("height", "740px");
    await assertInside(origin, dossier);
    await assertInside(actions, dossier);

    const [workspaceWidth, columns] = await Promise.all([
      workspace.evaluate((element) => element.clientWidth),
      room.evaluate(
        (element) => getComputedStyle(element).gridTemplateColumns,
      ),
    ]);
    expect(columns.split(" ")).toHaveLength(workspaceWidth >= 1060 ? 2 : 1);

    if (viewport.height === 600) {
      expect(
        await page.evaluate(
          () =>
            document.documentElement.scrollHeight >
            document.documentElement.clientHeight,
        ),
      ).toBe(true);
    }

    const hasHorizontalOverflow = await pageHasHorizontalOverflow(page);
    expect(
      hasHorizontalOverflow,
      JSON.stringify(
        hasHorizontalOverflow ? await horizontalOverflowReport(page) : {},
        null,
        2,
      ),
    ).toBe(false);
  });
}

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
    expect(
      await statement.evaluate((element) => element.clientHeight),
    ).toBeGreaterThan(0);
    await assertInside(origin, dossier);
    await assertInside(actions, dossier);

    const [firstButton, secondButton] = await Promise.all([
      buttons.nth(0).boundingBox(),
      buttons.nth(1).boundingBox(),
    ]);
    expect(firstButton).not.toBeNull();
    expect(secondButton).not.toBeNull();
    expect(Math.abs(firstButton.y - secondButton.y)).toBeLessThanOrEqual(1);

    for (const item of await page
      .locator("[data-verification-gap-item]")
      .all()) {
      await assertInside(item, page.locator("[data-verification-gaps]"));
    }
    const hasHorizontalOverflow = await pageHasHorizontalOverflow(page);
    expect(
      hasHorizontalOverflow,
      JSON.stringify(
        hasHorizontalOverflow ? await horizontalOverflowReport(page) : {},
        null,
        2,
      ),
    ).toBe(false);
  });
}

for (const viewport of [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
]) {
  test(`keeps the message rail as the only scrolling region under pressure at ${viewport.width}px`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await installIntakeRoomFixture(page, {
      messages: { count: 20, unbrokenLength: 1000 },
    });
    await page.goto(`/disputes/${CASE_ID}/intake`);

    const shell = page.locator(".intake-room__conversation");
    const rail = page.locator(".conversation-stream__messages");
    const composer = page.locator(".conversation-stream__composer");
    const textarea = composer.locator("textarea");
    const actionRow = composer.locator(":scope > div");

    await expect(shell).toHaveCSS("height", "740px");
    await expect(composer).toHaveCSS("height", "132px");
    expect(
      await rail.evaluate(
        (element) => element.scrollHeight > element.clientHeight,
      ),
    ).toBe(true);
    expect(await textarea.evaluate((element) => element.clientHeight)).toBe(72);
    const composerMetrics = await composer.evaluate((element) => {
      const action = element.querySelector(":scope > div");
      const text = action?.querySelector("span");
      const button = action?.querySelector("button");
      const box = (node) => ({
        clientHeight: node?.clientHeight ?? null,
        scrollHeight: node?.scrollHeight ?? null,
        clientWidth: node?.clientWidth ?? null,
        scrollWidth: node?.scrollWidth ?? null,
        textContent: node?.textContent?.trim() ?? null,
        lineHeight: node ? getComputedStyle(node).lineHeight : null,
        padding: node ? getComputedStyle(node).padding : null,
        whiteSpace: node ? getComputedStyle(node).whiteSpace : null,
        flexShrink: node ? getComputedStyle(node).flexShrink : null,
      });
      return {
        composer: box(element),
        action: box(action),
        text: box(text),
        button: box(button),
      };
    });
    expect(
      composerMetrics.composer.scrollHeight <=
        composerMetrics.composer.clientHeight + 1,
      JSON.stringify(composerMetrics, null, 2),
    ).toBe(true);
    expect(
      composerMetrics.action.scrollHeight <=
          composerMetrics.action.clientHeight + 1 &&
        composerMetrics.action.scrollWidth <=
          composerMetrics.action.clientWidth + 1,
      JSON.stringify(composerMetrics, null, 2),
    ).toBe(true);
    await assertInside(actionRow, composer);
    await assertInside(actionRow.locator("span"), actionRow);
    await assertInside(actionRow.locator("button"), actionRow);
    await assertInside(composer, shell);
    expect(await pageHasHorizontalOverflow(page)).toBe(false);
  });
}

test("confines native modal keyboard traversal and restores the trigger", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installIntakeRoomFixture(page, {
    dossier: { summaryLength: 300, statementLength: 300, gapCount: 10 },
  });
  await page.goto(`/disputes/${CASE_ID}/intake`);

  const trigger = page.locator(
    '[data-dossier-fulltext-trigger="summary"] [data-expandable-trigger]',
  );
  await trigger.click();

  const dialog = page.locator("[data-dossier-fulltext-dialog]");
  await expect(dialog).toBeVisible();
  expect(await dialog.evaluate((element) => element.tagName)).toBe("DIALOG");
  expect(await dialog.evaluate((element) => element.open)).toBe(true);
  expect(
    await dialog.evaluate((element) => element.contains(document.activeElement)),
  ).toBe(true);

  await page.keyboard.press("Tab");
  await expect(dialog.locator("[data-dismiss-dossier-fulltext]")).toBeFocused();
  await page.keyboard.press("Tab");
  expect(
    await dialog.evaluate(
      (element) =>
        element.ownerDocument.activeElement === element.ownerDocument.body,
    ),
  ).toBe(true);
  await expect(trigger).not.toBeFocused();

  await page.keyboard.press("Tab");
  await expect(dialog.locator("[data-dismiss-dossier-fulltext]")).toBeFocused();

  await page.keyboard.press("Shift+Tab");
  expect(
    await dialog.evaluate(
      (element) =>
        element.ownerDocument.activeElement === element.ownerDocument.body,
    ),
  ).toBe(true);
  await expect(trigger).not.toBeFocused();

  await page.keyboard.press("Shift+Tab");
  await expect(dialog.locator("[data-dismiss-dossier-fulltext]")).toBeFocused();

  expect(
    await dialog.evaluate((element) => {
      const backgroundControl = element.ownerDocument.querySelector(
        ".conversation-stream__composer textarea",
      );
      backgroundControl?.focus();
      return element.ownerDocument.activeElement !== backgroundControl;
    }),
  ).toBe(true);

  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});
