<script setup>
import { computed } from "vue";
import {
  agentSpeakerPresentation,
  agentSpeakerPresentationForIdentity,
  agentSpeakerTone,
} from "../../utils/agentSpeakerPresentation";

const props = defineProps({
  role: { type: String, default: "" },
  identity: { type: String, default: "" },
  name: { type: String, default: "" },
});

const presentation = computed(() => {
  const fallback = agentSpeakerPresentationForIdentity(props.identity);
  const rolePresentation = agentSpeakerPresentation(
    props.role,
    fallback.identity,
  );
  return {
    identity: props.identity ? fallback.identity : rolePresentation.identity,
    name: props.name || (props.identity ? fallback.name : rolePresentation.name),
  };
});
const tone = computed(() =>
  agentSpeakerTone(props.role, presentation.value.identity),
);
const accessibleLabel = computed(
  () => `${presentation.value.identity} ${presentation.value.name} 正常发言：`,
);
</script>

<template>
  <span
    class="agent-speaker-label"
    :class="`agent-speaker-label--${tone}`"
    :aria-label="accessibleLabel"
    data-agent-speaker-label
    :data-agent-speaker-tone="tone"
  >
    <span class="agent-speaker-label__identity" aria-hidden="true">
      {{ presentation.identity }}
    </span>
    <span class="agent-speaker-label__name" aria-hidden="true">
      {{ presentation.name }}
    </span>
    <span class="agent-speaker-label__suffix" aria-hidden="true">正常发言：</span>
  </span>
</template>

<style scoped>
.agent-speaker-label {
  --speaker-tag-color: #596579;
  --speaker-tag-background: #eef2f6;
  --speaker-tag-border: #d6dee8;
  display: inline-flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  color: inherit;
  font: inherit;
  letter-spacing: 0;
}
.agent-speaker-label__identity {
  display: inline-flex;
  min-height: 20px;
  box-sizing: border-box;
  align-items: center;
  padding: 2px 7px;
  color: var(--speaker-tag-color);
  background: var(--speaker-tag-background);
  border: 1px solid var(--speaker-tag-border);
  border-radius: 6px;
  font-size: .88em;
  font-weight: 800;
  line-height: 1.25;
  white-space: nowrap;
}
.agent-speaker-label__name,
.agent-speaker-label__suffix {
  color: inherit;
  white-space: nowrap;
}
.agent-speaker-label--intake {
  --speaker-tag-color: #68243f;
  --speaker-tag-background: #95c9b6;
  --speaker-tag-border: #74b29c;
}
.agent-speaker-label--evidence {
  --speaker-tag-color: #5c2442;
  --speaker-tag-background: #77a9e7;
  --speaker-tag-border: #5f91cf;
}
.agent-speaker-label--judge {
  --speaker-tag-color: #fff0b8;
  --speaker-tag-background: #302e55;
  --speaker-tag-border: #77719c;
}
.agent-speaker-label--jury {
  --speaker-tag-color: #594700;
  --speaker-tag-background: #d6c2f7;
  --speaker-tag-border: #a48cdb;
}
.agent-speaker-label--review {
  --speaker-tag-color: #eafff4;
  --speaker-tag-background: #ce4040;
  --speaker-tag-border: #a72f35;
}
.agent-speaker-label--guide {
  --speaker-tag-color: #2e315f;
  --speaker-tag-background: #f5b84d;
  --speaker-tag-border: #d79a2e;
}
</style>
