import { mount } from "@vue/test-utils";
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
    expect(wrapper.text()).toContain("AI 建议非最终");
    expect(wrapper.find('[aria-live="polite"]').text()).toContain(
      "我正在整理双方主张。",
    );
  });

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

  it("gives the judge a wig, left law code, and right gavel", () => {
    const wrapper = mount(DigitalHuman, {
      props: {
        state: "SPEAKING",
        name: "衡衡",
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
