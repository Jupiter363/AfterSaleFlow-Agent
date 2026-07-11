import { expect, test } from "@playwright/test";
import {
  CASE_ID,
  INTERNAL_A2A_TEXT,
  installHearingCourtFixture,
} from "./fixtures/hearing-court.fixture.js";

async function openHearingCourt(page, options = {}) {
  await installHearingCourtFixture(page, options);
  await page.goto(`/disputes/${CASE_ID}/hearing`);
  await expect(page.locator("[data-hearing-courtroom-page]"))
    .toBeVisible();
}

async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
    const root = document.documentElement;
    const viewportWidth = root.clientWidth;
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
          visibility: getComputedStyle(element).visibility,
        };
      })
      .filter(({ left, right }) => left < -1 || right > viewportWidth + 1)
      .slice(0, 16);
    return {
      viewportWidth,
      scrollWidth: root.scrollWidth,
      offenders,
    };
  });
}

async function expectNoDocumentHorizontalOverflow(page) {
  const report = await horizontalOverflowReport(page);
  expect(
    report.scrollWidth <= report.viewportWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

async function expectCourtroomInsideWorkspace(page) {
  const geometry = await page.evaluate(() => {
    const workspace = document.querySelector(".room-shell__workspace");
    const courtroom = document.querySelector("[data-hearing-courtroom-page]");
    if (!workspace || !courtroom) return null;
    const workspaceRect = workspace.getBoundingClientRect();
    const courtroomRect = courtroom.getBoundingClientRect();
    return {
      workspace: {
        left: workspaceRect.left,
        right: workspaceRect.right,
        width: workspaceRect.width,
      },
      courtroom: {
        left: courtroomRect.left,
        right: courtroomRect.right,
        width: courtroomRect.width,
        height: courtroomRect.height,
      },
    };
  });
  expect(geometry).not.toBeNull();
  expect(geometry.courtroom.left).toBeGreaterThanOrEqual(
    geometry.workspace.left - 1,
  );
  expect(geometry.courtroom.right).toBeLessThanOrEqual(
    geometry.workspace.right + 1,
  );
  expect(geometry.courtroom.width).toBeLessThanOrEqual(
    geometry.workspace.width + 1,
  );
  expect(geometry.courtroom.height).toBeGreaterThanOrEqual(720);
  expect(geometry.courtroom.height).toBeLessThanOrEqual(820);
}
async function setHearingWorkspaceWidth(page, width) {
  await page.locator(".app-page").evaluate((element, nextWidth) => {
    element.style.setProperty("width", `${nextWidth}px`, "important");
    element.style.setProperty("max-width", "none", "important");
  }, width);
  const workspace = page.locator(".room-shell__workspace");
  await expect
    .poll(() => workspace.evaluate((element) => element.clientWidth))
    .toBe(width);
  return workspace;
}

async function gridColumnCount(locator) {
  return locator.evaluate((element) =>
    getComputedStyle(element)
      .gridTemplateColumns
      .split(/\s+/)
      .filter(Boolean).length,
  );
}

async function assertHorizontalContainment(
  page,
  outerSelector,
  childSelector,
) {
  const report = await page.evaluate(
    ({ outerSelector: outerQuery, childSelector: childQuery }) => {
      const outer = document.querySelector(outerQuery);
      if (!outer) return { missingOuter: outerQuery, offenders: [] };
      const outerRect = outer.getBoundingClientRect();
      const offenders = [...document.querySelectorAll(childQuery)]
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            id: element.getAttribute("data-court-message-id") ||
              element.querySelector("strong")?.textContent ||
              element.className,
            left: rect.left,
            right: rect.right,
          };
        })
        .filter(
          ({ left, right }) =>
            left < outerRect.left - 1 || right > outerRect.right + 1,
        );
      return {
        outer: {
          left: outerRect.left,
          right: outerRect.right,
          clientWidth: outer.clientWidth,
          scrollWidth: outer.scrollWidth,
        },
        offenders,
      };
    },
    { outerSelector, childSelector },
  );
  expect(report.missingOuter, JSON.stringify(report, null, 2)).toBeUndefined();
  expect(report.offenders, JSON.stringify(report, null, 2)).toEqual([]);
  expect(
    report.outer.scrollWidth <= report.outer.clientWidth + 1,
    JSON.stringify(report, null, 2),
  ).toBe(true);
}

async function expectOnlyDrawerOpen(page, side) {
  const openDrawers = page.locator("[data-evidence-drawer-open]");
  await expect(openDrawers).toHaveCount(1);
  await expect(openDrawers).toHaveAttribute("data-evidence-drawer-open", side);
}

async function expectDrawerFocusLoop(page, side) {
  const drawer = page.locator(`[data-evidence-drawer-open="${side}"]`);
  const closeButton = drawer.locator(`[data-close-evidence-drawer="${side}"]`);
  await expect(closeButton).toBeFocused();

  await page.keyboard.press("Shift+Tab");
  expect(
    await drawer.evaluate((element) => element.contains(document.activeElement)),
  ).toBe(true);
  await expect(closeButton).not.toBeFocused();

  await page.keyboard.press("Tab");
  await expect(closeButton).toBeFocused();
}
test("loads the deterministic hearing court in Chromium", async ({ page }) => {
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  await openHearingCourt(page);

  await expect(page.locator("[data-court-message-id]"))
    .toHaveCount(4);
  await expect(page.locator("body"))
    .not.toContainText(INTERNAL_A2A_TEXT);
  await expect(page.locator("[role=alert]"))
    .toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test("keeps three columns through 1220px and switches to drawers below it", async ({
  page,
}) => {
  await openHearingCourt(page);
  const courtroom = page.locator("[data-hearing-courtroom-page]");
  const launchers = page.locator(".evidence-drawer-launchers");

  for (const width of [1221, 1220]) {
    const workspace = await setHearingWorkspaceWidth(page, width);
    expect(await workspace.evaluate((element) => element.clientWidth))
      .toBe(width);
    expect(await gridColumnCount(courtroom)).toBe(3);
    await expect(launchers).toHaveCSS("display", "none");
  }

  const workspace = await setHearingWorkspaceWidth(page, 1219);
  expect(await workspace.evaluate((element) => element.clientWidth))
    .toBe(1219);
  expect(await gridColumnCount(courtroom)).toBe(1);
  await expect(launchers).toHaveCSS("display", "flex");
  await expect(courtroom).toHaveCSS("overflow", "clip");
  await expectNoDocumentHorizontalOverflow(page);
});

for (const viewportWidth of [1260, 1259, 1181, 1180]) {
  test(`keeps the fixed courtroom canvas contained at ${viewportWidth}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewportWidth, height: 900 });
    await openHearingCourt(page);

    expect(await gridColumnCount(page.locator("[data-hearing-courtroom-page]")))
      .toBe(1);
    await expect(page.locator(".evidence-drawer-launchers"))
      .toHaveCSS("display", "flex");
    await expectCourtroomInsideWorkspace(page);
    await expectNoDocumentHorizontalOverflow(page);
  });
}

for (const viewportWidth of [681, 680]) {
  test(`keeps all hearing stages and the input dock contained at ${viewportWidth}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewportWidth, height: 900 });
    await openHearingCourt(page);
    const progress = page.locator("[data-hearing-progress-track]");
    const stageCopies = page.locator("[data-round-progress-item] > div");

    await expect(page.locator("[data-round-progress-item]")).toHaveCount(3);
    expect(await gridColumnCount(progress)).toBe(3);
    await expect(stageCopies.first()).toHaveCSS(
      "display",
      viewportWidth === 680 ? "grid" : "flex",
    );
    await expect(page.locator("[data-hearing-stage-dock]"))
      .toHaveCSS("height", "122px");
    await expect(page.locator("[data-round-input-bar]"))
      .toHaveCSS("height", "154px");
    expect(await gridColumnCount(page.locator(".round-input-bar__composer")))
      .toBe(2);
    await expectCourtroomInsideWorkspace(page);
    await expectNoDocumentHorizontalOverflow(page);
  });
}
for (const viewport of [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
  { width: 1024, height: 600 },
]) {
  test(`does not overflow the document at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await openHearingCourt(page);

    await expectNoDocumentHorizontalOverflow(page);
  });
}

test("wraps a 200-character unbroken hearing error without document overflow", async ({
  page,
}) => {
  const errorText = "E".repeat(200);
  await page.setViewportSize({ width: 320, height: 568 });
  await openHearingCourt(page, { loadError: errorText });
  const alert = page.locator(".hearing-error");
  await expect(alert).toHaveText(errorText);
  const containment = await alert.evaluate((element) => {
    const canvas = element.closest(".courtroom-center");
    const canvasRect = canvas.getBoundingClientRect();
    const alertRect = element.getBoundingClientRect();
    return {
      alertLeft: alertRect.left,
      alertRight: alertRect.right,
      alertClientWidth: element.clientWidth,
      alertScrollWidth: element.scrollWidth,
      canvasLeft: canvasRect.left,
      canvasRight: canvasRect.right,
    };
  });
  expect(containment.alertLeft).toBeGreaterThanOrEqual(containment.canvasLeft - 1);
  expect(containment.alertRight).toBeLessThanOrEqual(containment.canvasRight + 1);
  expect(containment.alertScrollWidth).toBeLessThanOrEqual(
    containment.alertClientWidth + 1,
  );
  await expectNoDocumentHorizontalOverflow(page);
});
test("contains closed drawers, keeps sides mutually exclusive, and closes with Escape", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page);
  const leftTrigger = page.locator('[data-open-evidence-drawer="left"]');
  const rightTrigger = page.locator('[data-open-evidence-drawer="right"]');
  const initialMetrics = await horizontalOverflowReport(page);

  await expect(page.locator("[data-evidence-drawer-open]"))
    .toHaveCount(0);
  await expectNoDocumentHorizontalOverflow(page);

  await leftTrigger.click();
  await expectOnlyDrawerOpen(page, "left");
  await expectDrawerFocusLoop(page, "left");
  await expectNoDocumentHorizontalOverflow(page);

  await page.keyboard.press("Escape");
  await expect(page.locator("[data-evidence-drawer-open]"))
    .toHaveCount(0);
  await expect(leftTrigger).toBeFocused();
  await expectNoDocumentHorizontalOverflow(page);

  await rightTrigger.click();
  await expectOnlyDrawerOpen(page, "right");
  await expectDrawerFocusLoop(page, "right");
  await expectNoDocumentHorizontalOverflow(page);

  await page.keyboard.press("Escape");
  await expect(page.locator("[data-evidence-drawer-open]"))
    .toHaveCount(0);
  await expect(rightTrigger).toBeFocused();
  const closedMetrics = await horizontalOverflowReport(page);
  expect(closedMetrics.scrollWidth).toBeLessThanOrEqual(
    initialMetrics.scrollWidth + 1,
  );
  await expectNoDocumentHorizontalOverflow(page);
});

test("clears drawer dialog state without restoring hidden launcher focus when the canvas widens", async ({
  page,
}) => {
  await openHearingCourt(page);
  await setHearingWorkspaceWidth(page, 1219);
  const leftTrigger = page.locator('[data-open-evidence-drawer="left"]');
  const leftRail = page.locator('[data-rail-position="left"]');

  await leftTrigger.click();
  await expectOnlyDrawerOpen(page, "left");
  await expect(leftRail).toHaveAttribute("role", "dialog");
  await expect(leftRail).toHaveAttribute("aria-modal", "true");

  await setHearingWorkspaceWidth(page, 1220);
  await expect(page.locator("[data-evidence-drawer-open]")).toHaveCount(0);
  await expect(leftRail).not.toHaveAttribute("role", "dialog");
  await expect(leftRail).not.toHaveAttribute("aria-modal", "true");
  await expect(leftTrigger).not.toBeFocused();
  await expect(page.locator(".evidence-drawer-launchers"))
    .toHaveCSS("display", "none");
  await expectNoDocumentHorizontalOverflow(page);
});

test("keeps court ledger focus modal and restores its opener after button and backdrop closes", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page);
  await page.locator('[data-open-evidence-drawer="right"]').click();
  const ledgerTrigger = page.locator("[data-open-court-ledger]");
  const ledger = page.locator("[data-court-ledger-drawer]");
  const closeButton = ledger.getByRole("button", { name: "关闭庭审卷轴" });

  await ledgerTrigger.click();
  await expect(ledger).toBeVisible();
  await expect(closeButton).toBeFocused();

  await page.keyboard.press("Tab");
  await expect(closeButton).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(closeButton).toBeFocused();

  await closeButton.click();
  await expect(ledger).toHaveCount(0);
  await expect(ledgerTrigger).toBeFocused();
  await expectOnlyDrawerOpen(page, "right");

  await ledgerTrigger.click();
  await expect(closeButton).toBeFocused();
  await ledger.click({ position: { x: 5, y: 5 } });
  await expect(ledger).toHaveCount(0);
  await expect(ledgerTrigger).toBeFocused();
  await expectOnlyDrawerOpen(page, "right");
});

test("closes a stacked court ledger before its underlying evidence drawer", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page);
  const evidenceTrigger = page.locator('[data-open-evidence-drawer="right"]');
  await evidenceTrigger.click();
  const ledgerTrigger = page.locator("[data-open-court-ledger]");
  await ledgerTrigger.click();
  await expect(page.locator("[data-court-ledger-drawer]")).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(page.locator("[data-court-ledger-drawer]")).toHaveCount(0);
  await expectOnlyDrawerOpen(page, "right");
  await expect(ledgerTrigger).toBeFocused();

  await page.keyboard.press("Escape");
  await expect(page.locator("[data-evidence-drawer-open]")).toHaveCount(0);
  await expect(evidenceTrigger).toBeFocused();
});

test("keeps the hearing ledger close target at least 44px square", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page);
  await page.locator('[data-open-evidence-drawer="right"]').click();
  await page.locator("[data-open-court-ledger]").click();
  const closeButton = page.locator(".hearing-ledger header button");
  const box = await closeButton.boundingBox();

  expect(box).not.toBeNull();
  expect(box.width).toBeGreaterThanOrEqual(44);
  expect(box.height).toBeGreaterThanOrEqual(44);
  await expectNoDocumentHorizontalOverflow(page);
});
test("holds the 122px status and 154px input slots while the message rail scrolls", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page, { messages: { count: 20 } });
  const status = page.locator("[data-hearing-stage-dock]");
  const input = page.locator("[data-round-input-bar]");
  const rail = page.locator(".court-transcript__messages");

  await expect(status).toHaveCSS("height", "122px");
  await expect(input).toHaveCSS("height", "154px");
  expect(
    await rail.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
  expect(
    await rail.evaluate(
      (element) => getComputedStyle(element).overflowY,
    ),
  ).toBe("auto");
  await expectNoDocumentHorizontalOverflow(page);
});

test("returns the input-dock space to the reviewer transcript", async ({
  browser,
  baseURL,
}) => {
  const contextOptions = {
    baseURL,
    viewport: { width: 1024, height: 600 },
    locale: "zh-CN",
    colorScheme: "light",
    reducedMotion: "reduce",
  };
  const userContext = await browser.newContext(contextOptions);
  const reviewerContext = await browser.newContext(contextOptions);
  const userPage = await userContext.newPage();
  const reviewerPage = await reviewerContext.newPage();

  try {
    await openHearingCourt(userPage, { messages: { count: 20 } });
    await openHearingCourt(reviewerPage, {
      role: "PLATFORM_REVIEWER",
      messages: { count: 20 },
    });
    const userTranscriptHeight = await userPage
      .locator(".court-transcript")
      .evaluate((element) => element.clientHeight);
    const reviewerTranscriptHeight = await reviewerPage
      .locator(".court-transcript")
      .evaluate((element) => element.clientHeight);

    await expect(userPage.locator("[data-round-input-bar]"))
      .toHaveCount(1);
    await expect(reviewerPage.locator("[data-round-input-bar]"))
      .toHaveCount(0);
    await expect(reviewerPage.locator("[data-hearing-stage-dock]"))
      .toHaveCSS("height", "122px");
    expect(reviewerTranscriptHeight).toBeGreaterThan(
      userTranscriptHeight + 150,
    );
    await expectNoDocumentHorizontalOverflow(userPage);
    await expectNoDocumentHorizontalOverflow(reviewerPage);
  } finally {
    await userContext.close();
    await reviewerContext.close();
  }
});

test("contains 50 messages, a 2000-character statement, and 100 evidence cards", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHearingCourt(page, {
    messages: { count: 50, longMessageLength: 2000 },
    evidence: { count: 100 },
  });

  const messageRail = page.locator(".court-transcript__messages");
  await expect(page.locator("[data-court-message-id]"))
    .toHaveCount(50);
  await expect(page.locator("body"))
    .not.toContainText(INTERNAL_A2A_TEXT);
  expect(
    await messageRail.evaluate(
      (element) => element.scrollHeight > element.clientHeight,
    ),
  ).toBe(true);
  await assertHorizontalContainment(
    page,
    ".court-transcript__messages",
    ".court-transcript__messages [data-court-message-id]",
  );
  await expectNoDocumentHorizontalOverflow(page);

  for (const side of ["left", "right"]) {
    await page.locator(`[data-open-evidence-drawer="${side}"]`).click();
    await expectOnlyDrawerOpen(page, side);
    const railSelector = `[data-rail-position="${side}"]`;
    const pocketSelector = `${railSelector} .evidence-pocket`;
    const cardSelector = `${railSelector} .evidence-file-card`;
    const pocket = page.locator(pocketSelector);
    const cards = page.locator(cardSelector);

    await expect(cards).toHaveCount(50);
    expect(
      await pocket.evaluate(
        (element) => element.scrollHeight > element.clientHeight,
      ),
    ).toBe(true);
    await assertHorizontalContainment(page, pocketSelector, cardSelector);
    await cards.last().scrollIntoViewIfNeeded();
    await expect(cards.last()).toBeVisible();
    await assertHorizontalContainment(page, pocketSelector, cardSelector);
    await expectNoDocumentHorizontalOverflow(page);

    await page.keyboard.press("Escape");
    await expect(page.locator("[data-evidence-drawer-open]"))
      .toHaveCount(0);
  }
});
