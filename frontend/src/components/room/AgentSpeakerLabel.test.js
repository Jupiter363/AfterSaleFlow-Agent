import { mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import AgentSpeakerLabel from "./AgentSpeakerLabel.vue";

describe("AgentSpeakerLabel", () => {
  it("renders the intake identity as a yellow-aligned tag without a separator", () => {
    const wrapper = mount(AgentSpeakerLabel, {
      props: { role: "INTAKE_OFFICER" },
    });

    expect(wrapper.attributes("data-agent-speaker-tone")).toBe("intake");
    expect(wrapper.get(".agent-speaker-label__identity").text()).toBe("争议接待官");
    expect(wrapper.get(".agent-speaker-label__name").text()).toBe("小衡");
    expect(wrapper.text()).not.toContain("·");
  });

  it.each([
    ["EVIDENCE_CLERK", "evidence", "证据书记官", "小册"],
    ["PRESIDING_JUDGE", "judge", "主审法官", "小正"],
    ["JURY_PANEL", "jury", "AI 评审员", "小察"],
    ["REVIEW_COPILOT", "review", "审核解释官", "小译"],
  ])("maps %s to the %s identity tone", (role, tone, identity, name) => {
    const wrapper = mount(AgentSpeakerLabel, { props: { role } });

    expect(wrapper.attributes("data-agent-speaker-tone")).toBe(tone);
    expect(wrapper.get(".agent-speaker-label__identity").text()).toBe(identity);
    expect(wrapper.get(".agent-speaker-label__name").text()).toBe(name);
  });

  it("normalizes a legacy name-first label", () => {
    const wrapper = mount(AgentSpeakerLabel, {
      props: { identity: "小译 · 审核解释官" },
    });

    expect(wrapper.attributes("data-agent-speaker-tone")).toBe("review");
    expect(wrapper.get(".agent-speaker-label__identity").text()).toBe("审核解释官");
    expect(wrapper.get(".agent-speaker-label__name").text()).toBe("小译");
    expect(wrapper.text()).not.toContain("·");
  });

  it("uses the dominant clothing colors from the current portraits", () => {
    const source = readFileSync(
      "src/components/room/AgentSpeakerLabel.vue",
      "utf8",
    );

    expect(source).toMatch(/agent-speaker-label--intake[\s\S]*?#68243f[\s\S]*?#95c9b6/);
    expect(source).toMatch(/agent-speaker-label--evidence[\s\S]*?#5c2442[\s\S]*?#77a9e7/);
    expect(source).toMatch(/agent-speaker-label--judge[\s\S]*?#fff0b8[\s\S]*?#302e55/);
    expect(source).toMatch(/agent-speaker-label--jury[\s\S]*?#594700[\s\S]*?#d6c2f7/);
    expect(source).toMatch(/agent-speaker-label--review[\s\S]*?#eafff4[\s\S]*?#ce4040/);
    expect(source).toMatch(/agent-speaker-label--guide[\s\S]*?#2e315f[\s\S]*?#f5b84d/);
  });
});
