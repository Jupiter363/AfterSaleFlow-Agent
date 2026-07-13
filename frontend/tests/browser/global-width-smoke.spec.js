// 文件作用：自动化测试文件，验证 global-width-smoke.spec 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { expect, test } from "@playwright/test";
import {
  GLOBAL_CASE_IDS,
  GLOBAL_REVIEW_ID,
  installGlobalWidthFixture,
} from "./fixtures/global-width.fixture.js";

const routes = [
  {
    name: "dispute overview",
    path: "/disputes",
    role: "USER",
    ready: `[data-case-id="${GLOBAL_CASE_IDS.intake}"]`,
  },
  {
    name: "intake room",
    path: `/disputes/${GLOBAL_CASE_IDS.intake}/intake`,
    role: "USER",
    ready: "[data-case-detail-dossier]",
  },
  {
    name: "evidence room",
    path: `/disputes/${GLOBAL_CASE_IDS.evidence}/evidence`,
    role: "USER",
    ready: "[data-evidence-card]",
  },
  {
    name: "hearing court",
    path: `/disputes/${GLOBAL_CASE_IDS.hearing}/hearing`,
    role: "USER",
    ready: "[data-hearing-stage-dock]",
  },
  {
    name: "outcome",
    path: `/disputes/${GLOBAL_CASE_IDS.outcome}/outcome`,
    role: "USER",
    ready: ".verdict-card",
  },
  {
    name: "review queue",
    path: "/reviews",
    role: "PLATFORM_REVIEWER",
    ready: "[data-review-task]",
  },
  {
    name: "review workbench",
    path: `/reviews/${GLOBAL_REVIEW_ID}`,
    role: "PLATFORM_REVIEWER",
    ready: "[data-packet-status]",
  },
  {
    name: "agent console",
    path: "/agents",
    role: "PLATFORM_REVIEWER",
    ready: "[data-agent-console]",
  },
];

const compactViewports = [
  { width: 390, height: 844 },
  { width: 320, height: 568 },
];

// 业务位置：【前端浏览器回归测试】horizontalOverflowReport：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
async function horizontalOverflowReport(page) {
  return page.evaluate(() => {
    const root = document.documentElement;
    const viewportWidth = root.clientWidth;
    const offenders = [...document.querySelectorAll("body *")]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return {
          tag: element.tagName.toLowerCase(),
          id: element.id,
          className:
            typeof element.className === "string" ? element.className : "",
          left: Math.round(rect.left * 10) / 10,
          right: Math.round(rect.right * 10) / 10,
          width: Math.round(rect.width * 10) / 10,
          display: style.display,
          visibility: style.visibility,
          position: style.position,
        };
      })
      .filter(
        ({ display, visibility, left, right }) =>
          display !== "none" &&
          visibility !== "hidden" &&
          (left < -1 || right > viewportWidth + 1),
      )
      .sort((left, right) => right.right - left.right)
      .slice(0, 20);

    return {
      viewportWidth,
      scrollWidth: root.scrollWidth,
      bodyClientWidth: document.body.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      offenders,
    };
  });
}

for (const viewport of compactViewports) {
  for (const route of routes) {
    // 业务位置：【前端浏览器回归测试】test：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面夹具和拦截 API 响应 正确进入 房间、审核和结果页面的交互断言。上游：页面夹具和拦截 API 响应。下游：房间、审核和结果页面的交互断言。边界：测试只验证可见体验与协议。
    test(`${route.name} has no document overflow at ${viewport.width}px`, async ({
      page,
    }) => {
      const pageErrors = [];
      page.on("pageerror", (error) => pageErrors.push(error.message));
      await page.setViewportSize(viewport);
      await installGlobalWidthFixture(page, { role: route.role });

      await page.goto(route.path, { waitUntil: "domcontentloaded" });
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );

      await expect(page.locator(route.ready)).toBeVisible();

      const report = await horizontalOverflowReport(page);
      expect(
        pageErrors,
        `${route.path} emitted page errors at ${viewport.width}px`,
      ).toEqual([]);
      expect(
        report.scrollWidth <= report.viewportWidth + 1,
        `${route.path} overflowed at ${viewport.width}px:\n${JSON.stringify(
          report,
          null,
          2,
        )}`,
      ).toBe(true);
    });
  }
}
