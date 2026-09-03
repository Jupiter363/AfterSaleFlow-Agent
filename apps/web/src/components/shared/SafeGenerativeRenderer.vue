<!--
  文件作用：前端组件文件，封装可复用 UI、状态展示或业务交互单元。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed } from "vue";
import { parseGenerativeUi } from "../../schemas/generativeUi";
import SourceCitation from "./SourceCitation.vue";

const props = defineProps({ payload: { type: Object, required: true } });
const emit = defineEmits(["navigate", "open-source"]);
const result = computed(() => {
  try {
    return { value: parseGenerativeUi(props.payload), error: null };
  } catch (error) {
    return { value: null, error };
  }
});
</script>

<template>
  <div v-if="result.error" class="state-card state-error">
    <span class="state-icon">!</span>
    <div><strong>AI 展示内容已拦截</strong><p>输出未通过安全组件白名单校验。</p></div>
  </div>
  <div v-else class="generative-grid">
    <article
      v-for="(block, index) in result.value.blocks"
      :key="`${block.type}-${index}`"
      class="generative-block"
      :data-tone="block.tone || 'neutral'"
    >
      <span class="block-type">{{ block.type }}</span>
      <h3>{{ block.title }}</h3>
      <p v-if="block.body">{{ block.body }}</p>
      <strong v-if="block.value" class="metric-value">{{ block.value }}</strong>
      <div v-if="block.citations.length" class="citation-row">
        <SourceCitation
          v-for="citation in block.citations"
          :key="citation.sourceId"
          :source-id="citation.sourceId"
          :label="citation.label"
          @open="$emit('open-source', $event)"
        />
      </div>
      <button
        v-if="block.action"
        class="secondary-button"
        type="button"
        @click="$emit('navigate', block.action.target)"
      >
        {{ block.title }}
      </button>
    </article>
  </div>
</template>
