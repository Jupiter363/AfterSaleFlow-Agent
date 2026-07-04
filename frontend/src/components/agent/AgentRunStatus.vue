<script setup>
defineProps({ runs: { type: Array, default: () => [] } });
</script>

<template>
  <section class="panel agent-run-panel">
    <header class="panel-header">
      <div><span class="eyebrow">AGENT TRACE</span><h2>协作运行</h2></div>
      <span class="status-pill status-safe">受控运行</span>
    </header>
    <div v-if="!runs.length" class="inline-empty">还没有 Agent Run；每次运行都会记录模型、版本与成本。</div>
    <article v-for="run in runs" :key="run.id || run.agent_run_id" class="run-row">
      <span class="run-orb" :data-status="run.run_status || run.status" />
      <div>
        <strong>{{ run.agent_role || run.agent_id || "Agent" }}</strong>
        <small>{{ run.prompt_version || "prompt —" }} · {{ run.model || "model —" }}</small>
      </div>
      <code>{{ run.agent_run_id || run.id }}</code>
      <span>{{ run.latency_ms ? `${run.latency_ms} ms` : "等待指标" }}</span>
    </article>
  </section>
</template>
