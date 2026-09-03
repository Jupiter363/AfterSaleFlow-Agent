// 文件作用：自动化测试文件，验证 DigitalHuman.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import DigitalHuman, {
  DIGITAL_HUMAN_LAYOUT,
  DIGITAL_HUMAN_PERSONAS,
  DIGITAL_HUMAN_STATES,
} from "./DigitalHuman.vue";

const personaCases = [
  ["争议接待官", "intake", "headset-band", "service-tablet", "welcoming"],
  ["证据书记官", "evidence", "archivist-hairpin", "case-folder", "focused"],
  ["AI 法官", "judge", "judge-wig", "law-code-and-gavel", "judicial"],
  ["AI 评审团", "jury", "panel-soft-cap", "review-scorecard", "deliberative"],
  ["审核解释官", "review", "review-glasses", "magnifier", "analytical"],
  ["路线引导员", "guide", "guide-cap", "route-flag", "cheerful"],
];

const expectedHairStyles = {
  intake: "service-side-bob",
  evidence: "archivist-side-sweep",
  judge: "traditional-side-curls",
  jury: "panel-low-bang",
  review: "auditor-rounded-bob",
  guide: "guide-cropped-bang",
};

// 业务位置：【前端业务组件】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
describe("DigitalHuman", () => {
  it.each(DIGITAL_HUMAN_STATES)("renders the shared %s state", (state) => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state,
        name: "小衡",
        role: "争议接待官",
        message: "我正在整理双方主张。",
      },
    });

    expect(wrapper.attributes("data-state")).toBe(state);
    expect(wrapper.text()).toContain("小衡");
    expect(wrapper.text()).toContain("争议接待官");
    expect(wrapper.text()).toContain("AI 只提供非最终建议，最终裁决由平台审核员确认");
    expect(wrapper.find('[aria-live="polite"]').text()).toContain(
      "我正在整理双方主张。",
    );
  });

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
  it("provides a static reduced-motion presentation", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "THINKING",
        name: "小簿",
        role: "证据书记官",
        reducedMotion: true,
      },
    });

    expect(wrapper.classes()).toContain("digital-human--reduced-motion");
    expect(wrapper.find("[data-motion-orbit]").exists()).toBe(false);
  });

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
  it("keeps the orbit aligned inside the avatar svg coordinate system", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "IDLE",
        name: "小衡",
        role: "AI 法官",
      },
    });

    const orbit = wrapper.get("svg [data-motion-orbit]");
    expect(orbit.attributes()).toMatchObject({
      cx: "115",
      cy: "112",
      r: "94",
    });
    expect(wrapper.find(".digital-human__portrait > [data-motion-orbit]").exists())
      .toBe(false);
  });

  it("renders the production reception-officer portrait inside the shared state halo", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "LISTENING",
        name: "小衡",
        role: "争议接待官",
      },
    });

    const portrait = wrapper.get("[data-intake-officer-portrait]");
    expect(wrapper.attributes("data-persona")).toBe("intake");
    expect(portrait.attributes("href")).toContain("intake-officer.webp");
    expect(portrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(wrapper.get("[data-motion-orbit]").exists()).toBe(true);
  });

  it("renders the production judge portrait inside the shared state halo", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "THINKING",
        name: "小正",
        role: "AI 法官",
      },
    });

    const portrait = wrapper.get("[data-judge-portrait]");
    expect(wrapper.attributes("data-persona")).toBe("judge");
    expect(portrait.attributes("href")).toContain("judge.webp");
    expect(portrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(wrapper.find("[data-intake-officer-portrait]").exists()).toBe(false);
    expect(wrapper.get("[data-motion-orbit]").exists()).toBe(true);
  });

  it("renders the production evidence-clerk portrait inside the shared state halo", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "LISTENING",
        name: "小册",
        role: "证据书记官",
      },
    });

    const portrait = wrapper.get("[data-evidence-clerk-portrait]");
    expect(wrapper.attributes("data-persona")).toBe("evidence");
    expect(portrait.attributes("href")).toContain("evidence-clerk.webp");
    expect(portrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(wrapper.find("[data-intake-officer-portrait]").exists()).toBe(false);
    expect(wrapper.find("[data-judge-portrait]").exists()).toBe(false);
    expect(wrapper.get("[data-motion-orbit]").exists()).toBe(true);
  });

  it("renders the production review-explainer portrait inside the shared state halo", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "THINKING",
        name: "小译",
        role: "审核解释官",
      },
    });

    const portrait = wrapper.get("[data-review-explainer-portrait]");
    expect(wrapper.attributes("data-persona")).toBe("review");
    expect(portrait.attributes("href")).toContain("review-explainer.webp");
    expect(portrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(wrapper.find("[data-route-guide-portrait]").exists()).toBe(false);
    expect(wrapper.get("[data-motion-orbit]").exists()).toBe(true);
  });

  it("applies independent production portraits to jury A and jury B", () => {
    const juryA = mount(DigitalHuman, {
      props: {
        state: "THINKING",
        name: "小察",
        role: "AI 评审员",
        portraitVariant: "jury-a",
      },
    });
    const juryB = mount(DigitalHuman, {
      props: {
        state: "LISTENING",
        name: "小律",
        role: "AI 评审团",
        portraitVariant: "jury-b",
      },
    });

    const juryAPortrait = juryA.get("[data-jury-a-portrait]");
    const juryBPortrait = juryB.get("[data-jury-b-portrait]");
    expect(juryA.attributes("data-persona")).toBe("jury");
    expect(juryA.attributes("data-portrait-variant")).toBe("jury-a");
    expect(juryAPortrait.attributes("href")).toContain("jury-a.webp");
    expect(juryAPortrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(juryA.find("[data-jury-b-portrait]").exists()).toBe(false);
    expect(juryB.attributes("data-persona")).toBe("jury");
    expect(juryB.attributes("data-portrait-variant")).toBe("jury-b");
    expect(juryBPortrait.attributes("href")).toContain("jury-b.webp");
    expect(juryBPortrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(juryB.find("[data-jury-a-portrait]").exists()).toBe(false);
  });

  it("renders the production route-guide portrait inside the shared state halo", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "HANDOFF",
        name: "小途",
        role: "路线引导员",
      },
    });

    const portrait = wrapper.get("[data-route-guide-portrait]");
    expect(wrapper.attributes("data-persona")).toBe("guide");
    expect(portrait.attributes("href")).toContain("route-guide.webp");
    expect(portrait.attributes()).toMatchObject({
      x: "-15",
      y: "-8",
      width: "250",
      height: "250",
      preserveAspectRatio: "xMidYMid meet",
    });
    expect(wrapper.get("[data-motion-orbit]").exists()).toBe(true);
  });

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
  it("exposes a 30/40/30 portrait layout for costume-safe avatars", () => {
    expect(DIGITAL_HUMAN_LAYOUT).toEqual({
      headAccessory: 30,
      bodyCostume: 40,
      handProp: 30,
    });

    const expressions = Object.values(DIGITAL_HUMAN_PERSONAS).map(
      (profile) => profile.expression,
    );
    expect(new Set(expressions).size).toBeGreaterThanOrEqual(4);
    expect(
      Object.values(DIGITAL_HUMAN_PERSONAS).every(
        (profile) => profile.bodyCostume && profile.handProp,
      ),
    ).toBe(true);
  });

  it.each(personaCases)(
    "renders %s with precise head, costume, and hand-prop slots",
    (role, persona, headAccessory, handProp, expression) => {
      const wrapper = mount(DigitalHuman, {
        props: {
          state: "LISTENING",
          name: "小衡",
          role,
        },
      });

      expect(wrapper.attributes("data-persona")).toBe(persona);
      expect(wrapper.attributes("data-expression")).toBe(expression);
      expect(wrapper.classes()).toContain(`digital-human--${persona}`);
      const titleRow = wrapper.get(".digital-human__title-row");
      expect(titleRow.get("strong").text()).toBe("小衡");
      expect(titleRow.get("[data-digital-human-role]").text()).toBe(role);
      expect(wrapper.get("[data-face-expression]").attributes("data-face-expression"))
        .toBe(expression);

      expect(wrapper.get('[data-costume-zone="head-accessory"]').attributes())
        .toMatchObject({
          "data-proportion": "30",
          "data-accessory": headAccessory,
          "data-face-safe": "true",
          "data-forehead-overlap": "true",
          "data-face-fit": "forehead-and-cheek-wrap",
        });
      if (persona === "judge") {
        expect(wrapper.get('[data-wig-style="traditional-side-curls"]').exists())
          .toBe(true);
        expect(wrapper.get('[data-wig-top-curls="true"]').exists()).toBe(true);
        expect(wrapper.findAll(".digital-human__wig-top-curl")).toHaveLength(4);
      } else {
        expect(wrapper.get(`[data-hair-style="${expectedHairStyles[persona]}"]`).attributes())
          .toMatchObject({
            "data-hair-fit": "face-contour",
            "data-hair-style": expectedHairStyles[persona],
          });
        if (persona === "evidence") {
          expect(wrapper.get('[data-hair-style="archivist-side-sweep"]').attributes())
            .toMatchObject({
              "data-cheek-fit": "right-rounded",
              "data-sharp-corner": "false",
            });
        }
        if (persona === "jury") {
          expect(wrapper.get('[data-jury-headpiece="low-soft-cap"]').attributes())
            .toMatchObject({
              "data-headpiece-fit": "above-hairline",
              "data-forehead-band": "false",
            });
        }
      }
      expect(wrapper.get(".digital-human__face").attributes("r")).toBe("36");
      expect(wrapper.get('[data-forehead-cover="true"]').attributes())
        .toMatchObject({
          "data-overlap-depth": "deep",
          "data-hairline": "forehead",
        });
      expect(wrapper.get('[data-costume-zone="clothing"]').attributes())
        .toMatchObject({
          "data-proportion": "40",
          "data-costume-label": persona,
          "data-body-over-orbit": "true",
          "data-body-width": "narrow-arch",
        });
      expect(wrapper.get(".digital-human__torso-base").attributes("data-arch-shape"))
        .toBe("narrow-body");
      expect(wrapper.get('[data-costume-zone="hand-prop"]').attributes())
        .toMatchObject({
          "data-proportion": "30",
          "data-prop": handProp,
          "data-layer-order": "prop-over-hand",
          "data-prop-occlusion": "hands-frame-prop",
        });
      expect(wrapper.get("[data-prop-anchor]").attributes())
        .toMatchObject({
          "data-prop-anchor": "arm-behind-prop",
          "data-arm-anatomy": "upper-and-forearm",
          "data-arm-scale": "weight-bearing",
          "data-arm-outline": "filled-sleeve",
        });
      expect(wrapper.get(".digital-human__arm").attributes("data-arm-scale"))
        .toBe("weight-bearing");
      expect(wrapper.find(".digital-human__sleeve-outline").exists()).toBe(true);
      expect(wrapper.find(".digital-human__upper-arm").exists()).toBe(true);
      expect(wrapper.find(".digital-human__forearm").exists()).toBe(true);
      expect(wrapper.get("[data-prop-grip]").attributes("data-prop-grip"))
        .toBe("visible-under-prop");
      expect(wrapper.get(".digital-human__grip").attributes("data-grip-scale"))
        .toBe("palm-sized");
      expect(wrapper.find(".digital-human__palm").exists()).toBe(true);
      expect(wrapper.find("[data-accessory='medal']").exists()).toBe(false);
      expect(wrapper.find(".digital-human__badge").exists()).toBe(false);
      expect(wrapper.find(".digital-human__arm").exists()).toBe(true);
      expect(wrapper.find(".digital-human__grip").exists()).toBe(true);
      expect(wrapper.find("[data-face-cover='true']").exists()).toBe(false);
    },
  );

  it("matches every identity label to the current portrait clothing color", () => {
    const source = readFileSync("src/components/avatar/DigitalHuman.vue", "utf8");

    expect(source).toMatch(/digital-human--intake[\s\S]*?--persona-role-color: #68243f[\s\S]*?--persona-role-background: #95c9b6/);
    expect(source).toMatch(/digital-human--evidence[\s\S]*?--persona-role-color: #5c2442[\s\S]*?--persona-role-background: #77a9e7/);
    expect(source).toMatch(/digital-human--judge[\s\S]*?--persona-role-color: #fff0b8[\s\S]*?--persona-role-background: #302e55/);
    expect(source).toMatch(/digital-human--jury[\s\S]*?--persona-role-color: #594700[\s\S]*?--persona-role-background: #d6c2f7/);
    expect(source).toMatch(/digital-human--review[\s\S]*?--persona-role-color: #eafff4[\s\S]*?--persona-role-background: #ce4040/);
    expect(source).toMatch(/digital-human--guide[\s\S]*?--persona-role-color: #2e315f[\s\S]*?--persona-role-background: #f5b84d/);
  });

  // 业务位置：【前端业务组件】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 页面传入的案件、消息、证据或审核数据 正确进入 可复用的房间交互和展示事件。上游：页面传入的案件、消息、证据或审核数据。下游：可复用的房间交互和展示事件。边界：组件不直接跨越业务权限调用。
  it("gives the judge a wig, left law code, and right gavel", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "SPEAKING",
        name: "小正",
        role: "AI 法官",
      },
    });

    expect(wrapper.attributes("data-persona")).toBe("judge");
    expect(wrapper.attributes("data-expression")).toBe("judicial");
    expect(wrapper.get('[data-accessory="judge-wig"]').exists()).toBe(true);
    expect(wrapper.get('[data-wig-style="traditional-side-curls"]').exists()).toBe(true);
    expect(wrapper.get('[data-wig-top-curls="true"]').exists()).toBe(true);
    expect(wrapper.findAll(".digital-human__wig-top-curl")).toHaveLength(4);
    expect(wrapper.get('[data-prop="law-code-and-gavel"]').exists()).toBe(true);
    expect(wrapper.get('[data-left-prop="law-code"]').exists()).toBe(true);
    expect(wrapper.get('[data-right-prop="gavel"]').exists()).toBe(true);
    expect(wrapper.get("[data-gavel-head]").exists()).toBe(true);
    expect(wrapper.get("[data-gavel-handle]").exists()).toBe(true);
    expect(wrapper.get("[data-gavel-block]").exists()).toBe(true);
    expect(wrapper.find(".digital-human__badge").exists()).toBe(false);
  });
});
