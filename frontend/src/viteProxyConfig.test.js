// @vitest-environment node

import { describe, expect, it } from "vitest";
import config from "../vite.config.js";

describe("vite development proxy", () => {
  it("routes local API calls directly to the dev Java API by default", () => {
    expect(config.server.proxy["/api"].target).toBe("http://127.0.0.1:8080");
  });
});
