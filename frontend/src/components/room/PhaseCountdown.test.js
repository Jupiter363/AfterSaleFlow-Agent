import { mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import PhaseCountdown from "./PhaseCountdown.vue";

describe("PhaseCountdown", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-03T10:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("derives display time from the latest server clock", async () => {
    const wrapper = mount(PhaseCountdown, {
      props: {
        deadlineAt: "2026-07-03T12:00:00Z",
        serverNow: "2026-07-03T10:00:00Z",
        label: "举证剩余时间",
      },
    });

    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.text()).toContain("01:59:59");

    await wrapper.setProps({ serverNow: "2026-07-03T10:00:10Z" });
    expect(wrapper.text()).toContain("01:59:50");
  });

  it("shows zero without initiating a business transition", async () => {
    const wrapper = mount(PhaseCountdown, {
      props: {
        deadlineAt: "2026-07-03T10:00:00Z",
        serverNow: "2026-07-03T10:00:10Z",
      },
    });

    await vi.advanceTimersByTimeAsync(1000);

    expect(wrapper.text()).toContain("00:00:00");
    expect(wrapper.emitted("expired")).toBeUndefined();
    expect(wrapper.attributes("data-awaiting-server")).toBe("true");
  });
});
