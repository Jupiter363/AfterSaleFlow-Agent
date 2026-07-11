import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "./client";

const actor = { id: "user-local", role: "USER" };

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("apiRequest", () => {
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
