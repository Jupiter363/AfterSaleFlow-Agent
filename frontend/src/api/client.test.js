// 文件作用：自动化测试文件，验证 client.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "./client";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

// 业务位置：【前端 API/SSE 适配】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
describe("apiRequest", () => {
  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("aborts a request after timeoutMs with a stable timeout code", async () => {
    vi.useFakeTimers();
    vi.spyOn(globalThis, "fetch").mockImplementation((_url, options) =>
      new Promise((_resolve, reject) => {
        if (!options.signal) {
          reject(new Error("fetch did not receive an AbortSignal"));
          return;
        }
        options.signal.addEventListener(
          "abort",
          () => reject(new DOMException("The request was aborted", "AbortError")),
          { once: true },
        );
      }),
    );

    const requestError = apiRequest("/slow", actor, { timeoutMs: 1000 }).catch(
      (error) => error,
    );

    await vi.advanceTimersByTimeAsync(1000);

    expect(await requestError).toMatchObject({ code: "REQUEST_TIMEOUT" });
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("preserves caller cancellation when a timeout is also configured", async () => {
    vi.useFakeTimers();
    const callerAbort = new AbortController();
    vi.spyOn(globalThis, "fetch").mockImplementation((_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener(
          "abort",
          () => reject(new DOMException("The request was aborted", "AbortError")),
          { once: true },
        );
      }),
    );

    const requestError = apiRequest("/slow", actor, {
      signal: callerAbort.signal,
      timeoutMs: 1000,
    }).catch((error) => error);

    callerAbort.abort();

    expect(await requestError).toMatchObject({ name: "AbortError" });
    await vi.advanceTimersByTimeAsync(1000);
  });

  // 业务位置：【前端 API/SSE 适配】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面操作和访问令牌 正确进入 Java HTTP 请求或 Agent 流事件。上游：页面操作和访问令牌。下游：Java HTTP 请求或 Agent 流事件。边界：统一处理错误和取消，不能伪造服务端状态。
  it("keeps caller cancellation authoritative when it wins the timeout race", async () => {
    vi.useFakeTimers();
    const callerAbort = new AbortController();
    vi.spyOn(globalThis, "fetch").mockImplementation((_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener(
          "abort",
          () => reject(new DOMException("The request was aborted", "AbortError")),
          { once: true },
        );
      }),
    );
    setTimeout(() => callerAbort.abort(), 1000);

    const requestError = apiRequest("/slow", actor, {
      signal: callerAbort.signal,
      timeoutMs: 1000,
    }).catch((error) => error);

    vi.advanceTimersByTime(1000);

    expect(await requestError).toMatchObject({ name: "AbortError" });
  });
});
