import { disputeApi } from "../api/disputes";

const PREPARATION_SCHEMA = "intake-infrastructure-preparation.v1";
const ACCEPTED_STATUSES = new Set(["READY", "NOT_REQUIRED"]);
const transitions = new Map();

function requiredText(value, label) {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${label} is required`);
  }
  return value.trim();
}

function transitionIdentity(actor, caseId) {
  return [
    requiredText(actor?.id, "actor.id"),
    requiredText(actor?.role, "actor.role"),
    caseId,
  ].join("\n");
}

function validatePreparationResult(result) {
  if (
    !result ||
    typeof result !== "object" ||
    Array.isArray(result) ||
    result.schema_version !== PREPARATION_SCHEMA ||
    !ACCEPTED_STATUSES.has(result.status)
  ) {
    const failure = new Error("接待环境准备响应无效，请稍后重试");
    failure.code = "INTAKE_PREPARATION_PROTOCOL_INVALID";
    throw failure;
  }
  return result;
}

export function prepareCommittedIntakeAndNavigate({ actor, caseId, router }) {
  const committedCaseId = requiredText(caseId, "caseId");
  if (!router || typeof router.push !== "function") {
    throw new TypeError("router.push is required");
  }

  const identity = transitionIdentity(actor, committedCaseId);
  let transition = transitions.get(identity);
  if (!transition) {
    transition = {
      idempotencyKey: `intake-preparation:${committedCaseId}`,
      inFlight: null,
      prepared: false,
    };
    transitions.set(identity, transition);
  }
  if (transition.inFlight) return transition.inFlight;

  const actorSnapshot = { id: actor.id, role: actor.role };
  const execute = async () => {
    if (!transition.prepared) {
      const result = validatePreparationResult(
        await disputeApi.prepareIntake(
          actorSnapshot,
          committedCaseId,
          transition.idempotencyKey,
        ),
      );
      transition.prepared = true;
      transition.result = result;
    }
    await router.push(`/disputes/${encodeURIComponent(committedCaseId)}/intake`);
    return transition.result;
  };

  transition.inFlight = execute().finally(() => {
    transition.inFlight = null;
  });
  return transition.inFlight;
}
