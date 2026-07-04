const COMPONENT_TYPES = new Set([
  "status",
  "metric",
  "finding",
  "citation-list",
  "action",
]);
const ACTION_TYPES = new Set(["navigate", "toggle-source", "copy-citation"]);
const NAVIGATION_TARGETS = new Set([
  "overview",
  "evidence",
  "hearing",
  "deliberation",
  "review",
]);
const FORBIDDEN_TEXT =
  /<\s*\/?\s*(script|iframe|object|embed|style|html)|javascript:|https?:\/\/|\/api\/|\/internal\/|approve|execute/i;

function object(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${label} must be an object`);
  }
  return value;
}

function text(value, label, { required = true } = {}) {
  if (value == null && !required) return "";
  if (typeof value !== "string" || (required && !value.trim())) {
    throw new TypeError(`${label} must be non-empty text`);
  }
  if (value.length > 2_000 || FORBIDDEN_TEXT.test(value)) {
    throw new Error(`${label} contains unsafe content`);
  }
  return value.trim();
}

function citation(value) {
  const item = object(value, "citation");
  return {
    sourceId: text(item.sourceId, "citation.sourceId"),
    label: text(item.label, "citation.label"),
  };
}

function block(value) {
  const item = object(value, "block");
  if (!COMPONENT_TYPES.has(item.type)) {
    throw new Error(`component type is not allowed: ${item.type}`);
  }
  const parsed = {
    type: item.type,
    title: text(item.title, "block.title"),
  };
  if (item.body != null) parsed.body = text(item.body, "block.body");
  if (item.value != null) parsed.value = text(String(item.value), "block.value");
  if (item.tone != null) {
    if (!["neutral", "positive", "warning", "critical"].includes(item.tone)) {
      throw new Error("block tone is not allowed");
    }
    parsed.tone = item.tone;
  }
  parsed.citations = (item.citations || []).map(citation);
  if (item.type === "action") {
    const action = object(item.action, "block.action");
    if (!ACTION_TYPES.has(action.type)) {
      throw new Error(`action type is not allowed: ${action.type}`);
    }
    if (!NAVIGATION_TARGETS.has(action.target)) {
      throw new Error(`action target is not allowed: ${action.target}`);
    }
    parsed.action = { type: action.type, target: action.target };
  } else if (item.action != null) {
    throw new Error("actions are allowed only in action blocks");
  }
  return Object.freeze(parsed);
}

export function parseGenerativeUi(value) {
  const payload = object(value, "Generative UI payload");
  if (payload.version !== 1 || !Array.isArray(payload.blocks)) {
    throw new Error("unsupported Generative UI contract");
  }
  if (payload.blocks.length > 24) {
    throw new Error("Generative UI block budget exceeded");
  }
  return Object.freeze({
    version: 1,
    blocks: Object.freeze(payload.blocks.map(block)),
  });
}
