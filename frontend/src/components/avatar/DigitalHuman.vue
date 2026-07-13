<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script>
export const DIGITAL_HUMAN_STATES = [
  "IDLE",
  "LISTENING",
  "THINKING",
  "STREAMING",
  "SPEAKING",
  "COMPLETED",
  "HANDOFF",
  "ERROR",
];

export const DIGITAL_HUMAN_LAYOUT = {
  headAccessory: 30,
  bodyCostume: 40,
  handProp: 30,
};

export const DIGITAL_HUMAN_PERSONAS = {
  intake: {
    expression: "welcoming",
    silhouette: "service-uniform",
    headAccessory: "headset-band",
    bodyCostume: "service-jacket",
    handProp: "service-tablet",
    hairStyle: "service-side-bob",
  },
  evidence: {
    expression: "focused",
    silhouette: "archive-clerk",
    headAccessory: "archivist-hairpin",
    bodyCostume: "archive-vest",
    handProp: "case-folder",
    hairStyle: "archivist-side-sweep",
  },
  judge: {
    expression: "judicial",
    silhouette: "court-robe",
    headAccessory: "judge-wig",
    bodyCostume: "court-robe",
    handProp: "law-code-and-gavel",
    hairStyle: "traditional-side-curls",
  },
  jury: {
    expression: "deliberative",
    silhouette: "panel-council",
    headAccessory: "panel-soft-cap",
    bodyCostume: "panel-cape",
    handProp: "review-scorecard",
    hairStyle: "panel-low-bang",
  },
  review: {
    expression: "analytical",
    silhouette: "review-auditor",
    headAccessory: "review-glasses",
    bodyCostume: "audit-coat",
    handProp: "magnifier",
    hairStyle: "auditor-rounded-bob",
  },
  guide: {
    expression: "cheerful",
    silhouette: "route-guide",
    headAccessory: "guide-cap",
    bodyCostume: "guide-vest",
    handProp: "route-flag",
    hairStyle: "guide-cropped-bang",
  },
};

export const DIGITAL_HUMAN_HAIR_SHAPES = {
  intake: {
    back: "M75 108c-5-38 13-62 42-64 25-2 43 15 48 47-12-10-26-15-43-15-19 0-34 10-47 32Z",
    forehead: "M77 94c8-26 25-39 50-34 14 3 25 12 31 27-17-4-31-5-42-1-12 4-24 7-39 8Z",
  },
  evidence: {
    back: "M73 108c-2-36 17-61 47-62 24-1 41 15 45 44-13-4-27-4-42 0-19 5-35 12-50 18Z",
    forehead: "M76 95c10-29 31-42 58-33 11 4 20 11 25 24-17-3-34-1-51 6-13 6-23 8-32 3Z",
  },
  jury: {
    back: "M83 100c4-25 17-39 35-39 19 0 32 14 36 39-11-6-23-9-36-9s-24 3-35 9Z",
    forehead: "M88 93c12-10 46-10 60 0-11 5-22 8-34 8-10 0-18-3-26-8Z",
  },
  review: {
    back: "M75 111c-5-35 12-58 41-60 30-2 48 22 43 60-11-14-24-22-42-22s-31 8-42 22Z",
    forehead: "M78 95c8-26 27-38 53-33 14 3 23 12 27 27-11-4-22-6-34-6-17 0-32 4-46 12Z",
  },
  guide: {
    back: "M79 104c3-27 18-43 39-43 22 0 37 16 40 43-12-8-25-12-40-12s-27 4-39 12Z",
    forehead: "M85 91c12-11 48-11 64 0-13 6-25 9-37 9-10 0-19-3-27-9Z",
  },
};

export const DIGITAL_HUMAN_EXPRESSIONS = {
  welcoming: [
    "M95 101c4-4 10-4 14 0",
    "M126 101c4-4 10-4 14 0",
    "M105 119c7 8 17 8 24 0",
  ],
  focused: [
    "M95 101h14",
    "M126 101h14",
    "M108 121h20",
    "M92 92l20 3",
    "M124 95l20-3",
  ],
  judicial: [
    "M95 101h14",
    "M128 101h14",
    "M107 121c7 4 14 4 21 0",
    "M91 91c7-2 15-2 22 1",
    "M123 92c7-3 15-3 22-1",
  ],
  deliberative: [
    "M95 101c4-2 9-2 13 0",
    "M127 99c5-4 11-4 16 0",
    "M108 121c5 4 13 4 18 0",
  ],
  analytical: [
    "M94 100h16",
    "M126 100h16",
    "M109 121h18",
    "M91 91l20 4",
    "M125 95l20-4",
  ],
  cheerful: [
    "M94 101c4-5 10-5 15 0",
    "M126 101c4-5 10-5 15 0",
    "M104 119c8 10 19 10 28 0",
  ],
};
</script>

<script setup>
import { computed } from "vue";
import evidenceClerkPortrait from "../../assets/digital-humans/evidence-clerk.webp";
import intakeOfficerPortrait from "../../assets/digital-humans/intake-officer.webp";
import judgePortrait from "../../assets/digital-humans/judge.webp";
import juryAPortrait from "../../assets/digital-humans/jury-a.webp";
import juryBPortrait from "../../assets/digital-humans/jury-b.webp";
import routeGuidePortrait from "../../assets/digital-humans/route-guide.webp";

const props = defineProps({
  state: {
    type: String,
    default: "IDLE",
    validator: (value) => DIGITAL_HUMAN_STATES.includes(value),
  },
  name: { type: String, required: true },
  role: { type: String, required: true },
  message: { type: String, default: "我在这里，随时可以开始。" },
  portraitVariant: { type: String, default: "" },
  reducedMotion: { type: Boolean, default: false },
});

const stateLabels = {
  IDLE: "等候中",
  LISTENING: "认真倾听",
  THINKING: "整理线索",
  STREAMING: "正在生成",
  SPEAKING: "正在说明",
  COMPLETED: "本阶段完成",
  HANDOFF: "准备交接",
  ERROR: "需要人工协助",
};

const personaMatchers = [
  { persona: "intake", keywords: ["接待官", "受理"] },
  { persona: "evidence", keywords: ["书记官", "证据"] },
  { persona: "judge", keywords: ["法官", "裁决"] },
  { persona: "jury", keywords: ["评审员", "评审团", "陪审团", "圆桌"] },
  { persona: "review", keywords: ["审核解释官", "审核辅助官", "审核"] },
  { persona: "guide", keywords: ["路线引导员", "引导"] },
];

const stateLabel = computed(() => stateLabels[props.state]);
const persona = computed(() => {
  const matched = personaMatchers.find(({ keywords }) =>
    keywords.some((keyword) => props.role.includes(keyword)),
  );
  return matched?.persona || "guide";
});
const profile = computed(() => DIGITAL_HUMAN_PERSONAS[persona.value] || DIGITAL_HUMAN_PERSONAS.guide);
const expressionLines = computed(() =>
  DIGITAL_HUMAN_EXPRESSIONS[profile.value.expression] || DIGITAL_HUMAN_EXPRESSIONS.welcoming,
);
const hairShape = computed(() =>
  DIGITAL_HUMAN_HAIR_SHAPES[persona.value] || DIGITAL_HUMAN_HAIR_SHAPES.guide,
);
</script>

<template>
  <article
    class="digital-human"
    :class="[
      `digital-human--${persona}`,
      { 'digital-human--reduced-motion': reducedMotion },
    ]"
    :data-state="state"
    :data-persona="persona"
    :data-expression="profile.expression"
    :data-silhouette="profile.silhouette"
    :data-portrait-variant="portraitVariant || null"
  >
    <div class="digital-human__portrait" aria-hidden="true">
      <svg viewBox="0 0 220 220" role="presentation">
        <circle
          v-if="!reducedMotion"
          class="digital-human__orbit"
          cx="115"
          cy="112"
          r="94"
          data-motion-orbit
        />
        <path
          class="digital-human__halo"
          d="M38 113c0-50 31-86 78-86 46 0 76 35 76 86 0 48-31 82-77 82-45 0-77-34-77-82Z"
        />

        <g
          class="digital-human__body-layer"
          :data-costume-label="persona"
          data-costume-zone="clothing"
          :data-costume="profile.bodyCostume"
          :data-proportion="DIGITAL_HUMAN_LAYOUT.bodyCostume"
          data-body-over-orbit="true"
          data-body-width="narrow-arch"
        >
          <path
            class="digital-human__torso-base"
            data-arch-shape="narrow-body"
            d="M66 218c6-50 24-78 51-78s45 28 51 78H66Z"
          />
          <path class="digital-human__neck" d="M99 137h36l-7 19h-22Z" />

          <template v-if="persona === 'intake'">
            <path class="digital-human__costume-panel" d="M72 218c7-42 22-64 46-65l-8 65Z" />
            <path class="digital-human__costume-panel" d="M162 218c-7-42-22-64-46-65l8 65Z" />
            <path class="digital-human__costume-line" d="M93 155h48M95 168h44M104 153l14 20 14-20" />
          </template>

          <template v-else-if="persona === 'evidence'">
            <path class="digital-human__costume-panel" d="M72 218c8-44 24-66 49-67l-4 67Z" />
            <path class="digital-human__costume-panel digital-human__costume-panel--light" d="M124 154c23 11 36 32 42 64h-49Z" />
            <path class="digital-human__costume-line" d="M89 158l28 21 28-21M92 187h52" />
          </template>

          <template v-else-if="persona === 'judge'">
            <path class="digital-human__costume-panel digital-human__costume-panel--deep" d="M69 218c8-48 24-73 48-73s40 25 48 73H69Z" />
            <path class="digital-human__judge-collar" d="M94 147l23 31 23-31" />
            <path class="digital-human__costume-line digital-human__costume-line--gold" d="M99 162h36M88 190h58" />
          </template>

          <template v-else-if="persona === 'jury'">
            <path class="digital-human__costume-panel" d="M69 218c8-44 24-66 48-66s40 22 48 66H69Z" />
            <path class="digital-human__costume-cape" d="M83 158c18 16 50 16 68 0 0 16-9 28-34 28s-34-12-34-28Z" />
            <path class="digital-human__costume-line" d="M94 179c13 7 33 7 46 0" />
          </template>

          <template v-else-if="persona === 'review'">
            <path class="digital-human__costume-panel" d="M74 218c8-44 22-66 43-66s35 22 43 66H74Z" />
            <path class="digital-human__costume-panel digital-human__costume-panel--light" d="M91 154l26 24 26-24v64H91Z" />
            <path class="digital-human__costume-line" d="M90 188h54M100 164l17 15 17-15" />
          </template>

          <template v-else>
            <path class="digital-human__costume-panel" d="M73 218c7-45 21-67 44-67s37 22 44 67H73Z" />
            <path class="digital-human__costume-line" d="M90 159l27 22 27-22M98 174c12 7 26 7 38 0" />
            <path class="digital-human__guide-strap" d="M96 153l43 51" />
          </template>
        </g>

        <g
          class="digital-human__head-layer"
          data-costume-zone="head-accessory"
          :data-accessory="profile.headAccessory"
          data-face-safe="true"
          data-forehead-overlap="true"
          data-face-fit="forehead-and-cheek-wrap"
          :data-proportion="DIGITAL_HUMAN_LAYOUT.headAccessory"
        >
          <path
            v-if="persona !== 'judge'"
            class="digital-human__hair"
            data-hair-fit="face-contour"
            :data-hair-style="profile.hairStyle"
            :data-cheek-fit="persona === 'evidence' ? 'right-rounded' : null"
            :data-sharp-corner="persona === 'evidence' ? 'false' : null"
            :d="hairShape.back"
          />

          <g
            v-else
            class="digital-human__judge-wig"
            data-accessory="judge-wig"
            data-wig-style="traditional-side-curls"
          >
            <path class="digital-human__wig-cap" d="M79 92c2-34 17-51 38-51s36 17 38 51c-11-11-24-17-38-17s-27 6-38 17Z" />
            <g class="digital-human__wig-top" data-wig-top-curls="true">
              <circle class="digital-human__wig-top-curl" cx="99" cy="61" r="8" />
              <circle class="digital-human__wig-top-curl" cx="112" cy="56" r="8" />
              <circle class="digital-human__wig-top-curl" cx="125" cy="56" r="8" />
              <circle class="digital-human__wig-top-curl" cx="138" cy="61" r="8" />
            </g>
            <path class="digital-human__wig-fringe" d="M87 88c7-20 17-30 30-30s23 10 30 30c-9-7-19-10-30-10s-21 3-30 10Z" />
            <circle class="digital-human__wig-curl" cx="73" cy="84" r="8" />
            <circle class="digital-human__wig-curl" cx="71" cy="101" r="9" />
            <circle class="digital-human__wig-curl" cx="74" cy="119" r="9" />
            <circle class="digital-human__wig-curl" cx="80" cy="136" r="8" />
            <circle class="digital-human__wig-curl" cx="161" cy="84" r="8" />
            <circle class="digital-human__wig-curl" cx="163" cy="101" r="9" />
            <circle class="digital-human__wig-curl" cx="160" cy="119" r="9" />
            <circle class="digital-human__wig-curl" cx="154" cy="136" r="8" />
            <path class="digital-human__wig-line" d="M94 60c5 8 5 17 0 27M117 52v32M140 60c-5 8-5 17 0 27" />
          </g>

          <g
            v-if="persona === 'evidence'"
            class="digital-human__archivist-hairpin"
          >
            <circle class="digital-human__hair-bun" cx="77" cy="112" r="10" />
          </g>

          <g
            v-else-if="persona === 'jury'"
            class="digital-human__panel-soft-cap"
            data-jury-headpiece="low-soft-cap"
            data-headpiece-fit="above-hairline"
            data-forehead-band="false"
          >
            <path class="digital-human__headpiece-fill" d="M88 73c14-11 45-11 60 0l-7 12c-14-6-32-6-46 0Z" />
            <path class="digital-human__accessory-line" d="M95 76c12-5 34-5 46 0" />
          </g>

          <g
            v-else-if="persona === 'guide'"
            class="digital-human__guide-cap"
          >
            <path class="digital-human__headpiece-fill" d="M86 75h62l-10-18H96Z" />
            <path class="digital-human__accessory-line" d="M83 86c19-8 49-8 68 0" />
          </g>

          <circle class="digital-human__face" cx="117" cy="106" r="36" />
          <g
            class="digital-human__forehead-cover"
            data-forehead-cover="true"
            data-overlap-depth="deep"
            data-hairline="forehead"
          >
            <path
              v-if="persona === 'judge'"
              class="digital-human__forehead-wig"
              d="M78 92c6-27 20-40 39-40s33 13 39 40c-12-10-25-15-39-15s-27 5-39 15Z"
            />
            <path
              v-else-if="persona === 'jury'"
              class="digital-human__forehead-headpiece"
              d="M88 82c14-9 46-9 60 0l-6 10c-14-5-34-5-48 0Z"
            />
            <path
              v-else-if="persona === 'guide'"
              class="digital-human__forehead-headpiece"
              d="M85 78h64l-10-19H95Z"
            />
            <path
              v-else
              class="digital-human__forehead-hair"
              :d="hairShape.forehead"
            />
          </g>
          <g class="digital-human__face-lines" :data-face-expression="profile.expression">
            <path
              v-for="line in expressionLines"
              :key="line"
              class="digital-human__expression-line"
              :d="line"
            />
          </g>

          <g
            v-if="persona === 'intake'"
            class="digital-human__headset-band"
          >
            <path class="digital-human__headpiece-fill" d="M91 65c14-9 38-9 52 0l-7 10c-11-5-27-5-38 0Z" />
            <path class="digital-human__accessory-line" d="M76 105c-8 0-13 6-13 15s5 15 13 15" />
            <path class="digital-human__accessory-line" d="M158 105c8 0 13 6 13 15s-5 15-13 15" />
            <path class="digital-human__accessory-line" d="M169 127c0 13-10 21-28 21" />
            <circle class="digital-human__accessory-fill" cx="140" cy="148" r="4" />
          </g>

          <g
            v-else-if="persona === 'evidence'"
            class="digital-human__archivist-hairpin-front"
          >
            <path class="digital-human__headpiece-fill" d="M142 73l17 8-10 11-17-8Z" />
            <path class="digital-human__accessory-line" d="M137 84l18-10" />
          </g>

          <g
            v-else-if="persona === 'review'"
            class="digital-human__review-glasses"
          >
            <circle class="digital-human__accessory-line" cx="102" cy="102" r="10" />
            <circle class="digital-human__accessory-line" cx="132" cy="102" r="10" />
            <path class="digital-human__accessory-line" d="M112 102h10" />
          </g>
        </g>

        <g
          class="digital-human__hand-prop"
          data-costume-zone="hand-prop"
          :data-prop="profile.handProp"
          :data-proportion="DIGITAL_HUMAN_LAYOUT.handProp"
          data-layer-order="prop-over-hand"
          data-prop-occlusion="hands-frame-prop"
        >
          <g
            class="digital-human__arm"
            data-prop-anchor="arm-behind-prop"
            data-arm-anatomy="upper-and-forearm"
            data-arm-scale="weight-bearing"
            data-arm-outline="filled-sleeve"
          >
            <path
              class="digital-human__sleeve-outline"
              d="M82 158c-20 7-36 21-49 45l18 12c9-20 22-34 39-42Z"
            />
            <path
              class="digital-human__sleeve-outline"
              d="M153 158c20 7 36 21 49 45l-18 12c-9-20-22-34-39-42Z"
            />
            <path
              class="digital-human__upper-arm"
              d="M79 165c-14 7-24 17-31 31M156 165c14 7 24 17 31 31"
            />
            <path
              class="digital-human__forearm"
              d="M55 186c-7 5-12 12-16 21M180 186c7 5 12 12 16 21"
            />
          </g>
          <g
            class="digital-human__grip"
            data-prop-grip="visible-under-prop"
            data-grip-scale="palm-sized"
          >
            <ellipse class="digital-human__palm" cx="58" cy="191" rx="9" ry="12" transform="rotate(-24 58 191)" />
            <ellipse class="digital-human__palm" cx="163" cy="191" rx="9" ry="12" transform="rotate(24 163 191)" />
          </g>

          <template v-if="persona === 'intake'">
            <rect class="digital-human__prop-surface" x="28" y="160" width="42" height="48" rx="9" />
            <path class="digital-human__prop-line" d="M39 174h20M39 187h16" />
            <circle class="digital-human__prop-dot" cx="55" cy="201" r="3" />
          </template>

          <template v-else-if="persona === 'evidence'">
            <path class="digital-human__prop-surface" d="M27 160h42l9 10v39H27Z" />
            <path class="digital-human__prop-line" d="M38 177h28M38 190h23M38 201h17" />
          </template>

          <template v-else-if="persona === 'judge'">
            <g data-left-prop="law-code">
              <path class="digital-human__prop-surface" d="M27 158h38l8 9v43H27Z" />
              <path class="digital-human__prop-line" d="M38 176h24M38 190h20M38 201h14" />
              <path class="digital-human__prop-line digital-human__prop-line--gold" d="M66 158v51" />
            </g>
            <g data-right-prop="gavel">
              <path
                class="digital-human__gavel-handle digital-human__prop-line digital-human__prop-line--heavy"
                data-gavel-handle
                d="M157 170l34 34"
              />
              <g class="digital-human__gavel-head" data-gavel-head>
                <rect class="digital-human__prop-surface" x="159" y="151" width="35" height="14" rx="5" transform="rotate(45 176.5 158)" />
                <path class="digital-human__prop-line digital-human__prop-line--gold" d="M164 151l30 30" />
              </g>
              <ellipse class="digital-human__gavel-block" data-gavel-block cx="174" cy="207" rx="25" ry="7" />
            </g>
          </template>

          <template v-else-if="persona === 'jury'">
            <rect class="digital-human__prop-surface" x="149" y="158" width="46" height="44" rx="9" />
            <path class="digital-human__prop-line" d="M160 174h24M160 187h18" />
            <path class="digital-human__prop-check" d="M181 195l6 6 12-16" />
          </template>

          <template v-else-if="persona === 'review'">
            <circle class="digital-human__prop-lens" cx="169" cy="171" r="18" />
            <path class="digital-human__prop-line digital-human__prop-line--heavy" d="M182 184l19 19" />
            <path class="digital-human__prop-line" d="M160 171h18" />
          </template>

          <template v-else>
            <path class="digital-human__prop-line digital-human__prop-line--heavy" d="M154 157v50" />
            <path class="digital-human__prop-flag" d="M155 159l38 13-38 14Z" />
            <path class="digital-human__prop-line" d="M165 174h16" />
          </template>
        </g>

        <image
          v-if="persona === 'intake'"
          class="digital-human__raster-portrait"
          :href="intakeOfficerPortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-intake-officer-portrait
        />
        <image
          v-else-if="persona === 'evidence'"
          class="digital-human__raster-portrait digital-human__raster-portrait--evidence"
          :href="evidenceClerkPortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-evidence-clerk-portrait
        />
        <image
          v-else-if="persona === 'judge'"
          class="digital-human__raster-portrait digital-human__raster-portrait--judge"
          :href="judgePortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-judge-portrait
        />
        <image
          v-else-if="persona === 'jury' && portraitVariant === 'jury-a'"
          class="digital-human__raster-portrait digital-human__raster-portrait--jury-a"
          :href="juryAPortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-jury-a-portrait
        />
        <image
          v-else-if="persona === 'jury' && portraitVariant === 'jury-b'"
          class="digital-human__raster-portrait digital-human__raster-portrait--jury-b"
          :href="juryBPortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-jury-b-portrait
        />
        <image
          v-else-if="persona === 'guide'"
          class="digital-human__raster-portrait digital-human__raster-portrait--guide"
          :href="routeGuidePortrait"
          x="-15"
          y="-8"
          width="250"
          height="250"
          preserveAspectRatio="xMidYMid meet"
          data-route-guide-portrait
        />
      </svg>
      <span class="digital-human__state-dot" />
    </div>

    <div class="digital-human__copy">
      <div class="digital-human__identity">
        <div>
          <strong>{{ name }}</strong>
          <span>{{ role }}</span>
        </div>
        <small>{{ stateLabel }}</small>
      </div>
      <p aria-live="polite">{{ message }}</p>
      <span class="digital-human__boundary">AI 只提供非最终建议，最终裁决由平台审核员确认</span>
    </div>
  </article>
</template>

<style scoped>
.digital-human {
  --digital-human-card-height: 190px;
  --human-accent: #64b9ff;
  --costume-main: #64b9ff;
  --costume-trim: #fff7de;
  --costume-symbol: #647091;
  --costume-foundation: #d7ecff;
  --costume-panel: #77c5ff;
  --costume-panel-soft: #e8f6ff;
  display: grid;
  box-sizing: border-box;
  grid-template-columns: 148px minmax(0, 1fr);
  gap: 18px;
  align-items: center;
  height: var(--digital-human-card-height);
  min-height: var(--digital-human-card-height);
  padding: 18px;
  color: #20304a;
  background: linear-gradient(135deg, #ffffffd9, #f4f0ffde);
  border: 1px solid #dfe7f5;
  border-radius: 28px;
  box-shadow: 0 18px 50px #5f72a314;
}

.digital-human--intake {
  --costume-main: #52c7a7;
  --costume-trim: #fff1c8;
  --costume-symbol: #2c7d72;
  --costume-foundation: #bdeedd;
  --costume-panel: #68d0b5;
  --costume-panel-soft: #e3fbf3;
}
.digital-human--evidence {
  --costume-main: #5ba7ff;
  --costume-trim: #e9f6ff;
  --costume-symbol: #315f96;
  --costume-foundation: #c8e5ff;
  --costume-panel: #6db2ff;
  --costume-panel-soft: #edf7ff;
}
.digital-human--judge {
  --costume-main: #51577f;
  --costume-trim: #ffe1a3;
  --costume-symbol: #4d3d1f;
  --costume-foundation: #616895;
  --costume-panel: #555d91;
  --costume-panel-soft: #fff1c8;
}
.digital-human--jury {
  --costume-main: #a98cf5;
  --costume-trim: #fff0ff;
  --costume-symbol: #6a4ab3;
  --costume-foundation: #d9caff;
  --costume-panel: #a98cf5;
  --costume-panel-soft: #f4edff;
}
.digital-human--review {
  --costume-main: #3ea5b2;
  --costume-trim: #e7fffb;
  --costume-symbol: #26717b;
  --costume-foundation: #bde9ec;
  --costume-panel: #56b7c2;
  --costume-panel-soft: #e9fffb;
}
.digital-human--guide {
  --costume-main: #f5b84d;
  --costume-trim: #fff4cf;
  --costume-symbol: #9a6a18;
  --costume-foundation: #ffe3a2;
  --costume-panel: #f5bd58;
  --costume-panel-soft: #fff5dc;
}

.digital-human[data-state="LISTENING"] { --human-accent: #6ed5aa; }
.digital-human[data-state="THINKING"] { --human-accent: #a98cf5; }
.digital-human[data-state="STREAMING"] { --human-accent: #8f83f2; }
.digital-human[data-state="SPEAKING"] { --human-accent: #ff9c80; }
.digital-human[data-state="COMPLETED"] { --human-accent: #52c790; }
.digital-human[data-state="HANDOFF"] { --human-accent: #f5b84d; }
.digital-human[data-state="ERROR"] { --human-accent: #ed6e7f; }

.digital-human__portrait {
  position: relative;
  min-height: 148px;
}

.digital-human__portrait svg {
  position: relative;
  z-index: 1;
  width: 148px;
  height: 148px;
  filter: drop-shadow(0 13px 15px #5d6c9026);
}

.digital-human__orbit {
  fill: none;
  stroke: color-mix(in srgb, var(--human-accent), transparent 38%);
  stroke-width: 3;
  stroke-dasharray: 7 7;
  transform-box: fill-box;
  transform-origin: center;
  animation: human-orbit 12s linear infinite;
}

.digital-human__halo {
  fill: color-mix(in srgb, var(--human-accent), white 65%);
}
.digital-human--intake .digital-human__body-layer,
.digital-human--intake .digital-human__head-layer,
.digital-human--intake .digital-human__hand-prop,
.digital-human--evidence .digital-human__body-layer,
.digital-human--evidence .digital-human__head-layer,
.digital-human--evidence .digital-human__hand-prop,
.digital-human--judge .digital-human__body-layer,
.digital-human--judge .digital-human__head-layer,
.digital-human--judge .digital-human__hand-prop,
.digital-human--jury[data-portrait-variant="jury-a"] .digital-human__body-layer,
.digital-human--jury[data-portrait-variant="jury-a"] .digital-human__head-layer,
.digital-human--jury[data-portrait-variant="jury-a"] .digital-human__hand-prop,
.digital-human--jury[data-portrait-variant="jury-b"] .digital-human__body-layer,
.digital-human--jury[data-portrait-variant="jury-b"] .digital-human__head-layer,
.digital-human--jury[data-portrait-variant="jury-b"] .digital-human__hand-prop,
.digital-human--guide .digital-human__body-layer,
.digital-human--guide .digital-human__head-layer,
.digital-human--guide .digital-human__hand-prop {
  display: none;
}
.digital-human__raster-portrait {
  pointer-events: none;
}
.digital-human__raster-portrait--evidence,
.digital-human__raster-portrait--guide,
.digital-human__raster-portrait--judge,
.digital-human__raster-portrait--jury-a,
.digital-human__raster-portrait--jury-b {
  filter: drop-shadow(0 4px 5px rgba(65, 58, 91, .16));
}
.digital-human__hair {
  fill: #3d4162;
}
.digital-human__hair-bun {
  fill: #3d4162;
}
.digital-human__face {
  fill: #ffd8bd;
}
.digital-human__forehead-hair {
  fill: #3d4162;
}
.digital-human__forehead-wig {
  fill: #fff3d2;
  stroke: #81708e;
  stroke-width: 1.8;
  stroke-linejoin: round;
}
.digital-human__forehead-headpiece {
  fill: var(--costume-trim);
  stroke: var(--costume-symbol);
  stroke-width: 2.2;
  stroke-linejoin: round;
}
.digital-human__neck {
  fill: #f5c3a2;
}
.digital-human__expression-line {
  fill: none;
  stroke: #4b4460;
  stroke-width: 4;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.digital-human__torso-base {
  fill: var(--costume-foundation);
  opacity: 0.72;
}
.digital-human__costume-panel {
  fill: var(--costume-panel);
  stroke: var(--costume-symbol);
  stroke-width: 2;
  stroke-linejoin: round;
}
.digital-human__costume-panel--light {
  fill: var(--costume-panel-soft);
}
.digital-human__costume-panel--deep {
  fill: var(--costume-panel);
}
.digital-human__costume-cape {
  fill: var(--costume-panel-soft);
  stroke: var(--costume-symbol);
  stroke-width: 2.3;
  stroke-linejoin: round;
}
.digital-human__judge-collar {
  fill: none;
  stroke: #fff7de;
  stroke-width: 8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.digital-human__guide-strap {
  fill: none;
  stroke: var(--costume-symbol);
  stroke-width: 5;
  stroke-linecap: round;
}
.digital-human__costume-line {
  fill: none;
  stroke: var(--costume-symbol);
  stroke-width: 3.3;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.digital-human__costume-line--gold {
  stroke: var(--costume-trim);
}

.digital-human__wig-cap,
.digital-human__wig-fringe,
.digital-human__wig-curl,
.digital-human__wig-top-curl {
  fill: #fff3d2;
  stroke: #81708e;
  stroke-width: 1.6;
}
.digital-human__wig-fringe {
  fill: #fff8e5;
}
.digital-human__wig-top-curl {
  fill: #fff8e5;
}
.digital-human__wig-line {
  fill: none;
  stroke: #81708e;
  stroke-width: 1.8;
  stroke-linecap: round;
}
.digital-human__accessory-fill {
  fill: var(--costume-trim);
  stroke: var(--costume-symbol);
  stroke-width: 2.2;
}
.digital-human__headpiece-fill {
  fill: var(--costume-trim);
  stroke: var(--costume-symbol);
  stroke-width: 2.2;
  stroke-linejoin: round;
}
.digital-human__accessory-line {
  fill: none;
  stroke: var(--costume-symbol);
  stroke-width: 3;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.digital-human__arm {
  opacity: 0.96;
}
.digital-human__sleeve-outline {
  fill: color-mix(in srgb, var(--costume-panel), white 18%);
  stroke: var(--costume-symbol);
  stroke-width: 2.1;
  stroke-linejoin: round;
}
.digital-human__upper-arm,
.digital-human__forearm {
  fill: none;
  stroke: color-mix(in srgb, var(--costume-symbol), var(--costume-panel) 40%);
  stroke-linecap: round;
  stroke-linejoin: round;
}
.digital-human__upper-arm {
  stroke-width: 4.8;
}
.digital-human__forearm {
  stroke-width: 4.4;
  stroke: color-mix(in srgb, var(--costume-symbol), var(--costume-panel) 48%);
}
.digital-human__grip {
  opacity: 0.96;
}
.digital-human__palm {
  fill: #ffd8bd;
  stroke: #e8a982;
  stroke-width: 2.2;
}
.digital-human__prop-surface,
.digital-human__prop-flag {
  fill: var(--costume-trim);
  stroke: var(--costume-symbol);
  stroke-width: 2.4;
  stroke-linejoin: round;
}
.digital-human__prop-line,
.digital-human__prop-check {
  fill: none;
  stroke: var(--costume-symbol);
  stroke-width: 2.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.digital-human__prop-line--heavy {
  stroke-width: 4;
}
.digital-human__prop-line--gold {
  stroke: #d0a43d;
}
.digital-human__prop-lens {
  fill: color-mix(in srgb, var(--costume-trim), transparent 35%);
  stroke: var(--costume-symbol);
  stroke-width: 2.8;
}
.digital-human__gavel-block {
  fill: color-mix(in srgb, var(--costume-trim), #d0a43d 24%);
  stroke: var(--costume-symbol);
  stroke-width: 2.4;
}
.digital-human__prop-dot {
  fill: var(--costume-symbol);
}

.digital-human__state-dot {
  position: absolute;
  right: 7px;
  bottom: 17px;
  z-index: 2;
  width: 18px;
  height: 18px;
  background: var(--human-accent);
  border: 4px solid white;
  border-radius: 50%;
}

.digital-human__copy { min-width: 0; }
.digital-human__identity {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.digital-human__identity strong,
.digital-human__identity span { display: block; }
.digital-human__identity strong { font-size: 20px; }
.digital-human__identity span { margin-top: 3px; color: #667089; font-size: 13px; }
.digital-human__identity small {
  padding: 6px 10px;
  color: #374158;
  background: color-mix(in srgb, var(--human-accent), white 74%);
  border-radius: 999px;
}
.digital-human__copy p { margin: 14px 0 12px; line-height: 1.65; }
.digital-human__boundary {
  display: inline-flex;
  padding: 6px 10px;
  color: #675b7d;
  background: #f1ecff;
  border-radius: 10px;
  font-size: 12px;
}

.digital-human--reduced-motion .digital-human__portrait svg { filter: none; }

@keyframes human-orbit {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .digital-human__orbit { animation: none; }
}

@media (max-width: 560px) {
  .digital-human {
    grid-template-columns: 96px minmax(0, 1fr);
    height: auto;
    min-height: var(--digital-human-card-height);
    padding: 14px;
  }
  .digital-human__portrait { min-height: 96px; }
  .digital-human__portrait svg { width: 96px; height: 96px; }
}
</style>
