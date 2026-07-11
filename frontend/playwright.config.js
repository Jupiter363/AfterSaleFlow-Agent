import { defineConfig, devices } from "@playwright/test";

const playwrightPort = process.env.PLAYWRIGHT_PORT ?? "4173";
if (!/^\d+$/.test(playwrightPort)) {
  throw new Error("PLAYWRIGHT_PORT must be an integer between 1 and 65535");
}
const port = Number(playwrightPort);
if (!Number.isSafeInteger(port) || port < 1 || port > 65535) {
  throw new Error("PLAYWRIGHT_PORT must be an integer between 1 and 65535");
}
const baseURL = `http://127.0.0.1:${port}`;

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
    baseURL,
    locale: "zh-CN",
    colorScheme: "light",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  webServer: {
    command: `pnpm exec vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
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
