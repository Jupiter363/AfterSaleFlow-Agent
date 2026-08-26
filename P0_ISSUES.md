# P0 / Confirmed Bug Ledger

## P0-20260824-QWEN38-LITELLM-ROUTE-MISSING

- Severity: P0
- Status: FIXED_BACKEND_UAT_VERIFIED
- Component: Python model binding and LiteLLM deployment route
- Confirmed fact: After the Python runtime default was changed to `qwen3.8-max`, `/health/model` returned HTTP 503 and two fresh USER-side imports ended before Intake with `intake infrastructure preparation is unavailable`; Python recorded both `/ready/intake-preparation` requests as HTTP 503.
- Root cause and evidence: The running Python process requested model name `qwen3.8-max`, while the mounted LiteLLM configuration declared only `qwen3.7-max-2026-06-08`. The LiteLLM container itself remained healthy, but no provider route matched the requested 3.8 model, so the real model probe failed and the Intake readiness contract rejected preparation.
- Impact: No fresh case can enter Intake under the requested 3.8 runtime, so all model and downstream E2E stages are blocked before the first generation.
- Verification evidence: The application default, deployment default, and LiteLLM route now resolve the same `qwen3.8-max` identifier; the two focused repository-contract tests passed, the recreated proxy reported `Set models: qwen3.8-max`, and the live Python `/health/model` probe returned HTTP 200 with `model_status=CONNECTED`, `model=qwen3.8-max`.
- Identifying metadata: observed 2026-08-24; Python PID `63028`; Java AGENT worker PID `51848`; LiteLLM container `order-fulfillment-dispute-system-litellm-proxy-1`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`; browser route `/disputes`.

## P1-20260824-EVIDENCE-IMAGE-GRAPH-CONTRACT-REJECTED-6A8AC2C9-13

- Severity: P1
- Status: FIXED_BACKEND_VERIFIED_UAT_PENDING
- Component: Evidence V2 image-evidence model output contract
- Confirmed fact: In fresh browser UAT case `CASE_P9_6A8AC2C9_13`, the MERCHANT submitted exactly one PNG evidence item. The original entered the durable catalog as `待人工复核`; the Evidence clerk then durably streamed valid `MATERIAL_RECEIPT`, `EVIDENCE_OBSERVATION`, `EVIDENCE_ASSESSMENT`, and `EVIDENCE_REQUEST` frames before the unpublished terminal `ROOM_READINESS` frame was rejected and the browser received `GRAPH_CONTRACT_REJECTED`.
- Root cause and evidence: Python raised `EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE` at `EvidenceV2PublicOutputPolicy._validate_header_scope` specifically while checking `ROOM_READINESS.remaining_core_fact_ids`. The frozen invocation exposed ten legal fact IDs, and every fact ID in the four committed frames belonged to that set, so the rejected terminal frame contained at least one provider-generated ID outside the frozen matrix. The prompt explicitly forbids creating or rewriting that field and the provider-visible JSON Schema injects the ten IDs as an item enum, but `_authority_bound_output_type` only overrides `model_json_schema()`; the local Pydantic field remains `list[str]` and accepts an out-of-scope identifier. A direct local parse accepted `FACT_NOT_IN_FROZEN_MATRIX`, proving that provider failure to honor the nested enum is not caught until the later authority policy. Stable case `CASE_P9_6A8AC2C9_1` emitted a terminal readiness list entirely drawn from its own frozen matrix, confirming that the current input matrix is not malformed and that provider compliance had only been stochastic.
- Impact: The first of the required three consecutive full-chain UAT runs stopped at MERCHANT Evidence analysis and the success streak remains zero.
- Verification evidence: The live Evidence projector and terminal materializer now apply the same frozen-authority projection only to the optional readiness fact list, preserving legal IDs in provider order and discarding provider-created references; actionable observation and request bindings remain fail-closed. The focused old-red readiness regression and the adjacent out-of-scope focus rejection both passed (2/2).
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_13`; actor `merchant-local/MERCHANT`; route `/disputes/CASE_P9_6A8AC2C9_13/evidence`; run `target-evidence-run:4933f5f9388837658aa40b5b42802aa5`; public diagnostic `GRAPH_CONTRACT_REJECTED`; internal diagnostic `EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE`; submitted file `CASE_P9_6A8AC2C9_5-merchant-qc-record.png`.

## P1-20260824-INTAKE-SCHEMA-OMITTED-IN-COMPATIBILITY-MODE

- Severity: P1
- Status: ROOT_CAUSE_CONFIRMED
- Component: Intake prompt composition and LiteLLM compatibility-mode structured output
- Confirmed fact: The frozen MERCHANT Intake input from case `CASE_P9_6A8AC2C9_9` was replayed twice independently with `qwen3.7-max-2026-06-08`, Thinking disabled, temperature zero, and identical prompt fingerprint `eea06bd9f0615cec2013d559876eff32b799345b6ae3ba9a207050cd81d97205`. Each replay used the configured same-input full regeneration once; all four provider generations failed final Pydantic validation and repeatedly emitted the same incompatible field family, including `fact_id` instead of required `fact_key`, `normalized_statement` instead of the current respondent-attitude fields, and `handoff_status`/`next_step_instruction` instead of the current handoff contract.
- Root cause and evidence: The last full-chain UAT case `CASE_P9_6A8AC2C9_1` completed all ten Intake runs and reached Outcome while every LLM path still forced `json_mode=True`. The nearest pre-change Intake case `CASE_P9_6A8AC2C9_8` completed all six Intake runs, including its final turn at `2026-08-24 03:15:53 +08`. At `03:31:17`, the uncommitted runtime change introduced `llm_strict_json_schema_enabled=False` and replaced the four fixed `json_mode=True` calls with the disabled setting. `PromptComposer.render_user_prompt` still discards `output_schema` and says that a strict response structure is provided separately. The later `03:38:17` Intake prose edit did not restore the Schema. The first comparable substantive MERCHANT turn after restart, case `CASE_P9_6A8AC2C9_9` at `03:59:38 +08`, therefore carried neither `response_format=json_schema` nor a rendered Schema in the user prompt and failed final Pydantic validation after both provider generations.
- Impact: Intake generations can look complete and stream all ten ordered sections, then deterministically fail final validation, trigger a full regeneration, and fail again; the browser sees a reset/error instead of a committed turn and the case cannot proceed.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_9`; command `intake-message:615665b1152d31b4afa67a63f07aa1f1`; logical run `target-intake-run:615665b1152d31b4afa67a63f07aa1f1`; replay reports `.local-dev/intake-qwen37-same-prompt-run-1-20260824.json` and `.local-dev/intake-qwen37-same-prompt-run-2-20260824.json`.

## P1-20260823-EVIDENCE-OBSERVATION-SLOT-DUPLICATED

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Evidence V2 structured model output validation
- Confirmed fact: Fresh browser-UAT cases `CASE_P9_6A8AC2C9_2` and `CASE_P9_6A8AC2C9_3` both failed an evidence batch after public observations had already streamed. Case 3 displayed the secondary recovery diagnostic `GRAPH_TERMINAL_BINDING_CONFLICT`; neither staged image entered the durable evidence catalog.
- Root cause and evidence: The Python service recorded the original `GraphContractError` at `app.agents.evidence_clerk.v2_policy:_validate_observation` with code `EVIDENCE_V2_OBSERVATION_SLOT_DUPLICATED`. In case 3, run `target-evidence-run:73dad83a19433173aefe4cb6739868d0` had already durably committed the receipt and observations `OBS_SLOT_001` and `OBS_SLOT_002`; a later model observation reused an observation label and the backend uniqueness guard rejected the turn. Java then closed the stream, the graph command became `STREAM_INTERRUPTED`, and terminal reconciliation surfaced `GRAPH_TERMINAL_BINDING_CONFLICT`, masking the original diagnostic.
- Impact: Later evidence batches can persist and both parties can appear complete while the failed case workflow cannot seal the dossier or enter Hearing, so downstream UAT must use a fresh case.
- Verification evidence: Evidence V2 now preserves repeated model-owned observation labels as ordered source bindings and no longer applies duplicate-slot or missing-assessment completeness rejection in either the live projector or terminal assembler. The focused authority/binding regression group passed (4/4).
- Identifying metadata: observed 2026-08-23; cases `CASE_P9_6A8AC2C9_2` and `CASE_P9_6A8AC2C9_3`; batch `EVIDENCE_BATCH_b8a40c4f21f441d1b03e7a68caa996f8`; command `evidence-submit:EVIDENCE_BATCH_b8a40c4f21f441d1b03e7a68caa996f8`; internal diagnostic `EVIDENCE_V2_OBSERVATION_SLOT_DUPLICATED`.

## P1-20260823-EVIDENCE-V2-FRAME-HEADER-INVALID

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Evidence V2 provider frame-header contract
- Confirmed fact: In fresh generic-identity browser-UAT case `CASE_P9_6A8AC2C9_5`, the USER batch containing two staged TXT files failed before any verification text was emitted or evidence committed. The browser received public diagnostic `GRAPH_CONTRACT_REJECTED`, while both files remained staged and retryable.
- Root cause and evidence: TXT source units were assembled with `basis=PARSED_TEXT`, while `EvidenceObservationFrameHeaderV2.observation_kind` excluded `PARSED_TEXT` and accepted only a fixed semantic enum. Images did not trigger the defect because `IMAGE_PIXELS` appeared in both the assembled source basis and the enum. Earlier TXT turns succeeded only when the model voluntarily translated `PARSED_TEXT` into `PARSED_RECORD`, `PARSED_PARTY_STATEMENT`, or `PARSED_TRANSACTION_STATUS`; copying the authoritative source basis into the provider header caused `EvidenceV2PublicOutputPolicy._start_frame` to raise `EVIDENCE_V2_FRAME_HEADER_INVALID`. Python and Java also contained additional model-semantic completeness/enum checks capable of reproducing the same class of failure at later boundaries.
- Impact: A valid staged text-evidence batch cannot reach Evidence assessment or durable commit, blocking USER evidence completion and the downstream Hearing chain.
- Verification evidence: Evidence V2 now treats provider semantic fields as optional/model-owned, rebinds stream sequence from server array order, accepts `PARSED_TEXT` and model-defined semantic labels, binds missing values as null/empty, and keeps attachment/source/fact authority checks. Focused Python checks passed for `PARSED_TEXT`, missing fields, and out-of-scope fact rejection (3/3); focused Java terminal parsing/scope tests passed (6/6).
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A8AC2C9_5`; batch `EVIDENCE_BATCH_01c4572c555444acb37e3800564e5b2b`; command `evidence-submit:EVIDENCE_BATCH_01c4572c555444acb37e3800564e5b2b`; run `target-evidence-run:859c09cdb144350bbda842b521865e25`; graph thread `grt.v1.000df7de69c930aaa486867f4f612885`; internal diagnostic `EVIDENCE_V2_FRAME_HEADER_INVALID`.

## P1-20260823-INTAKE-PUBLIC-PROJECTION-PYDANTIC-VALIDATION-ESCAPES

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Intake governed stream projection after the model response
- Confirmed fact: Fresh browser-UAT case `CASE_P9_6A89E0EC_5` emitted a complete Intake room reply and four ordered dossier sections, then the same request ended as `GRAPH_STREAM_PROTOCOL_REJECTED` before any terminal proposal was committed.
- Root cause and evidence: The request made exactly one provider call and exposed ordered sections 1 through 4 before the Python graph source raised an uncaught Pydantic `ValidationError` at `pydantic.main:__init__:263`. The governed callback fast path accepted atomic visible deltas up to 64 KiB, then constructed an `AgentStreamPayload` whose per-delta limit is 4,096 characters without applying the oversized-atomic-card suppression already present on the mirrored LangGraph message path. A later ordered section above 4,096 characters therefore failed during public payload construction rather than being omitted until the authoritative terminal refresh.
- Impact: A valid but large later Intake card terminates an otherwise visible generation with HTTP 500; the accepted room text and provisional board are discarded, and the case cannot safely resume because the failed event binding has already consumed its sequence.
- Verification evidence: The callback fast path now applies the same AgentStreamV2 sizing projection as the mirrored path: oversized atomic ordered cards are omitted from provisional SSE, while long room text is split losslessly into bounded deltas. The focused regression passed (1/1). An adjacent pre-existing executor test remains red because its fixture returns a bare `object` without the now-required `ingress_kind`; that failure occurs before stream projection and is unrelated to this repair.
- Identifying metadata: observed 2026-08-23; activation `p9act.v1.f713fd806655f0dc32b5fa398f199588`; case `CASE_P9_6A89E0EC_5`; command `intake-message:923bad604a6f3dde81d1bbd56166b5c5`; route `/disputes/CASE_P9_6A89E0EC_5/intake`.

## BUG-20260822-FRONTEND-REJECTS-GENERATION-RESET

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Intake browser stream event handling for same-request full regeneration
- Confirmed fact: Fresh Thinking-mode case `CASE_P9_6A888D81_8`, non-Thinking `qwen3.8-max` case `CASE_P9_6A8AC2C9_9`, and the first post-fix streak candidate `CASE_P9_6A8AC2C9_15` all reached a backend `generation_reset` event during an Intake turn, but the visible Intake page terminated the stream with `不支持的数字人流事件：generation_reset` instead of clearing provisional output and continuing the replacement generation. In case `_9` the first generation emitted sequences 1-29, `generation_reset` was durably recorded at sequence 30, the replacement generation emitted sequences 31-59, and the run ended with `AGENT_OUTPUT_SCHEMA_INVALID` at sequence 60. In case `_15`, the USER second reply was durably accepted and the browser failed only when the subsequent model generation entered the same reset path.
- Root cause and evidence: The Python/Java stream contract and durable event ledger include the explicit `generation_reset` event, while `frontend/src/api/agentStream.js` omits it from `AGENT_STREAM_EVENTS` and therefore throws `AGENT_STREAM_EVENT_UNSUPPORTED`. The browser stops consuming that run at sequence 30, while durable events prove that the second generation continued server-side through sequence 60 and was no longer observable by that browser stream.
- Impact: Any final-format failure that correctly triggers full same-input regeneration becomes a user-visible Intake failure in the browser; provisional content cannot be safely replaced, the browser loses the eventual terminal event, and frontend state can diverge from the durable run state.
- Verification evidence: The browser protocol normalizer now accepts the authoritative V3 `generation_reset` event; the shared stream store atomically clears provisional text, cards, structured fields, and frame projections while preserving the same attempt authority; the Intake dossier overlay observes the same reset boundary. Focused protocol and same-attempt projection regressions both passed (2/2), proving that replacement text is shown without retaining the rejected generation.
- Identifying metadata: first observed 2026-08-22 on case `CASE_P9_6A888D81_8`; reproduced 2026-08-24 on cases `CASE_P9_6A8AC2C9_9` and `CASE_P9_6A8AC2C9_15`; known logical run `target-intake-run:615665b1152d31b4afa67a63f07aa1f1`, attempt `target-intake-attempt:615665b1152d31b4afa67a63f07aa1f1:1`; latest route `/disputes/CASE_P9_6A8AC2C9_15/intake`.

## BUG-20260822-THINKING-INTAKE-STREAM-EXCEEDS-TIMEOUT

- Severity: P1
- Status: REOPENED_UAT_BLOCKED
- Component: Qwen Thinking Intake provider stream and Graph AgentRun retry boundary
- Confirmed fact: With `qwen3.7-max` and `LLM_ENABLE_THINKING=true`, fresh case `CASE_P9_6A888D81_7` emitted provisional USER Intake text but did not complete its first formal-turn provider stream before the configured 120-second model timeout; the provisional browser output was cleared and the same logical run entered another activity attempt.
- Root cause and evidence: The formal run began at `2026-08-22 03:21:32.527 +08` and its next activity attempt began at `03:23:33.412 +08`, about 120.9 seconds later. Python recorded `ModelStreamInterrupted` / `GRAPH_PROVIDER_STREAM_INTERRUPTED` and did not record `AGENT_OUTPUT_SCHEMA_INVALID`, proving the generation failed at the stream deadline rather than final Schema validation.
- Impact: Thinking-mode Intake can consume retry authority after already displaying provisional text, substantially delay the room, and still fail without a committed final frame if a later attempt also exceeds the deadline.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.d02d4ae6c34b35c9979c3fc4340c3790`; case `CASE_P9_6A888D81_7`; logical run `target-intake-run:cf769a7b491e36b4896c898586ee010f`; first attempt `target-intake-attempt:cf769a7b491e36b4896c898586ee010f:1`; second activity attempt `agent-attempt:2a9f4fe97111a8b06dbc7fc3cb84bf89`.

## BUG-20260822-INTAKE-NARRATIVE-ARRAY-STRUCTURE-LEAK

- Severity: P1
- Status: FIXED_UAT_VERIFIED
- Component: Intake V3 model output and persisted dossier narrative arrays
- Confirmed fact: Intake proposal artifacts for two fresh UAT cases contain output-protocol fragments such as `next_questions`, `sequence`, `value`, `],` and `net_questions: []` as elements of narrative arrays that should contain reviewer-facing business text.
- Root cause and evidence: The immutable Python proposal artifacts already contain the fragments before Java persistence or frontend projection; the response remains schema-valid because the affected fields accept arbitrary non-empty strings. Case `CASE_P9_6A888D81_2` persisted multiple section and field tokens, and case `CASE_P9_6A888D81_4` persisted `net_questions: []` in `missing_information.nice_to_have_gaps`.
- Impact: The Intake right panel renders meaningless entries such as `核验` and `核验next_questions`, and the polluted dossier text can be consumed by downstream stages as if it were business content.
- Verification evidence: Prompt composition for the base Intake contract and both role profiles includes the narrative-array leaf contract, and both focused prompt tests passed (2/2), but live UAT case `CASE_P9_6A888D81_5` still emitted `next_questions: []` inside `nice_to_have_gaps` on generation attempt 2. That attempt then ended after section 9 with `GRAPH_PROVIDER_STREAM_INTERRUPTED`; the run terminated as `GRAPH_CONTRACT_REJECTED` before persistence of the generated dossier revision.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.d02d4ae6c34b35c9979c3fc4340c3790`; cases `CASE_P9_6A888D81_2` and `CASE_P9_6A888D81_4`; command `intake-message:8834bc61fb843d98848c00a8e628a123`; proposal artifact hashes `17bd142...` and `ed6b4701...`.

## BUG-20260822-DYNAMIC-UAT-ACTOR-NORMALIZED-TO-DEMO

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Frontend persisted actor identity bootstrap
- Confirmed fact: Opening fresh case `CASE_P9_6A888D81_1` as its registered USER actor fails with `actor id and role do not match a case party` while the browser sends the fixed demo identity `user-local` instead of the case USER `five-round-uat-user-583c798327e2453fa16de18b6c906ef2`.
- Root cause and evidence: `frontend/src/state/actor.js::storedActor` accepts only the fixed ID paired with each demo role; any persisted non-demo USER ID is replaced with `user-local` during module bootstrap even when the ID is a valid authoritative case participant.
- Impact: A browser cannot open any freshly imported UAT case whose authoritative parties use collision-safe generated IDs, although direct API UAT for the same case is authorized.
- Verification evidence: The actor-state regression suite preserves a generated USER ID while retaining fixed demo-role switching (4/4 passed), and `GET /api/disputes/CASE_P9_6A888D81_1` succeeds with the restored authoritative USER headers.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.d02d4ae6c34b35c9979c3fc4340c3790`; case `CASE_P9_6A888D81_1`; route `/disputes/CASE_P9_6A888D81_1/intake`; response message `actor id and role do not match a case party`.

## BUG-20260821-OUTCOME-REVIEWER-ACTION-PROJECTION-MISSING

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Outcome HTTP projection of the persisted human final decision
- Confirmed fact: `human_review_record` persists both `ai_decision_action` and `reviewer_decision_action`, and the Review decision response returns both fields, but `GET /api/disputes/{caseId}/outcome` omits them from `final_decision`.
- Root cause and evidence: `FinalDecisionView` exposes only conclusion, explanation, review reason, source, confirmation, and approved plan; `CaseOutcomeService.finalDecision` therefore drops the authoritative decision type, both persisted action codes, approval record ID, and decision timestamp while building the Outcome response.
- Impact: The execution-result page cannot bind its final execution event directly to the reviewer-owned downstream action and would have to infer a code from nested plan content or legacy draft text.
- Identifying metadata: observed 2026-08-21; persistence fields `human_review_record.ai_decision_action` and `human_review_record.reviewer_decision_action`; response path `data.final_decision`; focused verification `CaseOutcomeServiceTest` + `CaseOutcomeControllerTest` (11/11 passed).

## BUG-20260821-UI-CURRENT-RULES-REVIEW-EPOCH-MISSING

- Severity: P2
- Status: RESOLVED
- Component: Current-rules UI review fixture and Target Review activation authority
- Confirmed fact: `POST /api/reviews/UI_TASK_CURRENT_RULES_20260821/decision` with a valid `APPROVE` command returns HTTP 409 `CASE_STATUS_INVALID` and `target-origin review task has no active Review epoch`.
- Root cause and evidence: Packet `UI_PACKET_CURRENT_RULES_20260821` declares the Target Hearing discriminator pair `prompt_version=hearing-flow.v2` and `profile_version=hearing-judge-v2`, so the Review service correctly requires an active, ready, Temporal-owned Review epoch; the fixture has none. Request `REQ_da3d3485a4674764910079c30d3c870c` was rejected before approval persistence.
- Impact: The current-rules UI fixture renders as an actionable in-review task but cannot commit a final review decision or enter the Outcome chain.
- Identifying metadata: observed 2026-08-21; task `UI_TASK_CURRENT_RULES_20260821`; case `UI_CASE_CURRENT_RULES_20260821`; packet `UI_PACKET_CURRENT_RULES_20260821`; trace `TRACE_102c6a6e83caf3507d1cfceea8367f0d`.

## BUG-20260821-JUDGE-V3-DECISION-ACTION-CONTRACT-DRIFT

- Severity: P1
- Status: FOCUSED_FIX_VERIFIED_UAT_PENDING
- Component: Judge V2 authoritative artifact and ReviewPacket projection
- Confirmed fact: Review tasks `hearing-review-task-1a4fd910efa430db8699508aaa6de695` and `hearing-review-task-0fcc80845c7939a3987527286416a0c8` are bound to authoritative artifacts labeled `adjudication_draft.v3`, but their nested drafts have no `decision_action`; they instead contain the removed legacy field `recommended_decision=REQUIRES_HUMAN_REVIEW` and a legacy `FURTHER_VERIFICATION` remedy.
- Root cause and evidence: The schema label and payload contract already diverge in the authoritative Judge V2 artifact. Review packet `hearing-review-packet-e10fbdd492e53aa095a982277c51fe8d` copied the first malformed body, while live packet `hearing-review-packet-2e7d601ebec5380ca9a017cf24c519f6` for the second task exposes `prompt_version=hearing-flow.v2`, `profile_version=hearing-judge-v2`, and no supported `decision_action`. `ReviewDecisionPlanPolicy` therefore rejects approval before decision persistence; the drift is upstream of the frontend and is not a Review UI fallback.
- Impact: The frozen AI recommendation cannot resolve to one of the nine executable decision codes, so the Review UI truthfully renders “需人工复核” instead of a bounded downstream action and the backend cannot commit an approval or enter the Outcome execution chain.
- Identifying metadata: observed 2026-08-21; cases `CASE_P9_6A88011D_1` and `CASE_P9_6A878C2A_1`; Judge V2 AgentRun `target-hearing-run:ccf2af04ce8d33a4ac014aa43f737999`; second packet `hearing-review-packet-2e7d601ebec5380ca9a017cf24c519f6`; artifact schema `adjudication_draft.v3`; missing field `decision_action`.

## BUG-20260821-REVIEW-RISK-PANEL-GRID-COLLAPSE

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Human Review operation-column risk checklist
- Confirmed fact: On review task `hearing-review-task-0fcc80845c7939a3987527286416a0c8`, the redesigned `重点复核` section disappeared from the right operation column even though all four risk rows remained mounted in the DOM.
- Root cause and evidence: The finite-height `.review-operation-room__scroll` Grid used default implicit `auto` rows while its content exceeded the scroll viewport. Because `.review-risk-strip` also clipped overflow, its automatic minimum size could collapse; CDP measured a 1.6px panel border box with 332px of child scroll content. After the scroll Grid used intrinsic `max-content` rows, the same panel measured 333px with four visible rows, no horizontal overflow, and no Vite error overlay.
- Impact: Reviewers could not see the mandatory risk checklist immediately before recording the human final decision.
- Identifying metadata: observed 2026-08-21; route `/reviews/hearing-review-task-0fcc80845c7939a3987527286416a0c8`; affected selector `[data-review-risk-panel]`.

## BUG-20260821-FRESH-MERCHANT-SECOND-INTAKE-AGENT-RUN-ERROR

- Severity: P1
- Status: REPAIR_IN_PROGRESS
- Component: Fresh canonical MERCHANT second Intake AgentRun
- Confirmed fact: Fresh case `CASE_P9_6A87D285_1` completed import, USER Intake and confirmation, MERCHANT opening, and the first MERCHANT formal turn, but the second MERCHANT stream ended at `respondent_stream_2/agent_run_error` after 233.219 seconds and was not formally accepted.
- Root cause and evidence: AgentRun `target-intake-run:084e7f57f4253b048e6b08e1f181ef2b` exhausted its two authorized provider calls before either could commit a final frame. Attempts 1 and 2 each emitted public deltas, then lost the Graph lease while the Graph runtime logged repeated PostgreSQL connection timeouts, cancelled-query rollback failures, and discarded bad pool connections; both ended `GRAPH_LEASE_LOST/CREATE_NEXT_ATTEMPT`. Their Graph command rows each consumed one provider call and ended `GRAPH_STREAM_INTERRUPTED`. Attempt 3 was registered with `provider_attempts_remaining=0`, made no provider call, and was immediately aborted as `GRAPH_RETRY_BUDGET_EXHAUSTED`; the logical AgentRun then failed closed as non-retryable `GRAPH_CONTRACT_REJECTED` with no committed attempt or final frame.
- Impact: The case remains in MERCHANT Intake before its second formal turn, so respondent confirmation, Evidence, Hearing, the frozen adjudication dossier, Judge V1/V2, Review, and execution cannot proceed.
- Regression evidence: On 2026-08-22, fresh Max-model case `CASE_P9_6A888D81_1` completed its first USER Intake command, but second command `intake-message:07fb9fc2f9f431299bdd5494673ace08` ended `ABORTED/GRAPH_STREAM_INTERRUPTED` after Python logged PostgreSQL connection timeouts followed by `GraphLeaseLostError`; no Graph result was committed. A second isolated case `CASE_P9_6A888D81_2`, created with browser-native parties `user-local / merchant-local`, reproduced the same boundary: first USER command completed in 34.250 seconds, while the second ended `ABORTED/GRAPH_STREAM_INTERRUPTED` after 139.891 seconds with the same persistence timeout and lease-loss sequence.
- Recovery-blocking evidence: The failed run `target-intake-run:72b88c991b233c72bcec58a763ad4c1e` remains `PENDING` with attempt 1 durably marked `ABORTED/GRAPH_LEASE_LOST`, `error_retryable=true`, and `termination_code=CREATE_NEXT_ATTEMPT`, but no attempt 2 was allocated. A live `jcmd` thread dump at 2026-08-22 02:24 +08 showed the sole Java API `scheduling-1` thread parked for about 2,648 seconds inside Temporal `executeMultiOperation` from `TemporalCommandOutboxRelay.recoverDeliveries`; the five-second `AgentRunRecoveryScheduler` had not run again after its startup scan.
- Latest confirmed evidence: After Java recovery resumed, the same logical run allocated attempt 2 (`agent-attempt:ba10d8178a0803240bc5c8517f197914`) without duplicating the accepted Intake message. Its Graph lease was last renewed at `2026-08-22 02:31:36.275096+08`, expired at `02:32:06.275096+08`, and the command then aborted as `GRAPH_STREAM_INTERRUPTED`; the Java attempt ended `ABORTED/GRAPH_LEASE_LOST`. Attempt 3 was allocated with no remaining provider budget, ended `GRAPH_CONTRACT_REJECTED/FAIL_LOGICAL_RUN`, and the logical run is now `FAILED/UNCOMMITTED` with no committed attempt or final frame.
- Event-loop starvation evidence: A third fresh case, `CASE_P9_6A888D81_3`, used canonical fixture parties `user-local / merchant-local`; USER turn 1 completed in 31.176 seconds, while USER turn 2 and its server-allocated retry each reached five pre-model Graph checkpoints and then produced no later checkpoint before ending `GRAPH_STREAM_INTERRUPTED` after 56.908 and 61.473 seconds. During both model streams the independent readiness probe timed out and polling was suspended, while post-failure PostgreSQL inspection showed five idle runtime connections and no retained transaction or row-lock waiter. The production async stream path performs accumulated-document projection (`_AsyncStructuredStreamState.accept`) and final Pydantic validation (`completed`) synchronously on the event-loop thread; the projector rescans the growing JSON document for each provider event. A focused scheduling regression reproducing a held projection is green only when this work is executed outside the event-loop thread.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.dd6c86fcebb4b477ae9788d655c9c4fa`; case `CASE_P9_6A87D285_1`; failure stage `respondent_stream_2`.

## BUG-20260821-FRESH-INTAKE-FIRST-RUN-ERROR

- Severity: P1
- Status: REGRESSED_CONFIRMED
- Component: Fresh canonical USER Intake first AgentRun
- Confirmed fact: Fresh case `CASE_P9_6A87CFCE_1` completed import, infrastructure preparation, and USER opening, but its first Intake stream terminated with `agent_run_error` after 36.938 seconds before any formal USER turn was accepted.
- Root cause and evidence: AgentRun `target-intake-run:d374a48408a136ee938b1c71254cd591` ended `ABORTED/AGENT_OUTPUT_SCHEMA_INVALID` after emitting all ten ordered Intake sections. Its `CASE_MATRIX.fact_rows` declared `FACT_ORDER_REF`, `FACT_PRODUCT_NAME`, `FACT_CLAIM_TYPE`, and `FACT_REQUESTED_OUTCOME`, while `summary_source_fact_keys` referenced the same four row identifiers as `FACt_ORDER_REF`, `FACt_PRODUCT_NAME`, `FACt_CLAIM_TYPE`, and `FACt_REQUESTED_OUTCOME`. The provider-facing `CaseFactMatrixDeltaV2` requires at least one exact summary-to-row key match before the downstream fact-key normalizer runs, so the case-only identifier drift rejected an otherwise complete first-turn output.
- Impact: This fresh candidate stopped before Evidence and cannot validate the repaired E1 role contract or any downstream judge/review/outcome stage.
- Verification evidence: Three focused regressions passed for ordered-provider unique case-drift rebinding, ambiguous case-collision rejection, unknown-reference rejection, duplicate provider-row normalization, and preservation of the respondent source-binding path.
- Regression evidence: Fresh browser-UAT case `CASE_P9_6A8866C5_2` imported successfully, then its automatic USER Intake opening run `AGENT_RUN_225231709b344579a37059a9106b3dd9` failed before any formal message or turn memory was persisted. Python rejected the model result because its case-matrix delta referenced unknown fact `FACT_ORDER_REF`. Durable replay contains only `start -> usage -> error` (`AGENT_OUTPUT_SCHEMA_INVALID`, `visible_output_emitted=false`) and no `generation_reset`; `_structured_stream_generation_limit` fixes any `json_frame_objects` public-frame stream to one generation, so the V3 Intake path did not consume the configured second provider attempt after final validation failure.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.3dbda349134e6cbde821b2b95d772116`; case `CASE_P9_6A87CFCE_1`; failure stage `initiator_stream_1`.

## BUG-20260821-HEARING-E1-TARGET-ROLES-NOT-CANONICAL

- Severity: P1
- Status: REGRESSED_CONFIRMED
- Component: Hearing E1 evidence-request target role contract
- Confirmed fact: Fresh canonical case `CASE_P9_6A87CA1A_2` froze M2 and generated E1, but the continuation stopped before either E1 batch because at least one persisted request did not expose the required exact role vector `["USER", "MERCHANT"]`.
- Root cause and evidence: All five persisted E1 requests carried only `["MERCHANT"]`. The prompt required the shared role vector, but the Python proposal assembler preserved whichever subset the provider returned, and the Java finalizer copied that subset into the durable request set. Both deterministic assembly boundaries now canonicalize every request to `["USER", "MERCHANT"]` while leaving fact scope as prompt guidance.
- Impact: No E1 batch has been submitted for the candidate case, so E2, the frozen adjudication dossier, Judge V1/V2, Review, and Outcome cannot proceed.
- Verification evidence: Two focused Python proposal tests and three focused Java persistence/binding tests passed. They prove provider role subsets become the shared vector, same-case evidence remains fact-level reusable, and cross-case evidence remains rejected.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.c4f5ff4ee6542f52efcbe6ebe277521f`; case `CASE_P9_6A87CA1A_2`; failure stage `hearing_e1_ready`.

## BUG-20260821-CANONICAL-MERCHANT-EVIDENCE-COMPLETION-NON_SUCCESS

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Canonical Evidence room MERCHANT completion without an upload
- Confirmed fact: Fresh case `CASE_P9_6A87CA1A_1` completed both Intake paths, the sole USER evidence upload/submission/stream/replay, the exact-one catalog guard, and USER completion/replay, but the MERCHANT no-upload completion call returned a non-success status at `canonical_merchant_evidence_complete_without_upload`.
- Root cause and evidence: The canonical client posts the MERCHANT completion immediately after the USER completion command and its replay. The backend exposes two exact, fail-closed process-revision race responses while that predecessor moves the authority boundary: `expected process revision is already reserved by an active command` and `expected process revision is stale`. The UAT helper admitted only the first shape. The failed request created no MERCHANT completion command material; once the USER command was applied, the same MERCHANT action returned HTTP 200 and sealed the room. Four focused client regressions now accept only those two authoritative race shapes and continue rejecting a foreign-case reservation and an unrelated room-epoch conflict.
- Impact: The evidence room is not yet sealed and the fresh case cannot enter Hearing, E2, judge, Review, or Outcome.
- Verification evidence: Four focused client regressions passed for the two exact retryable revision races and two fail-closed adjacent conflicts. Fresh case `CASE_P9_6A87CA1A_2` then hit the MERCHANT completion revision boundary, retried without changing the idempotency key, sealed the single-evidence room, entered Hearing, accepted both answer bundles, and froze M2.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.c4f5ff4ee6542f52efcbe6ebe277521f`; case `CASE_P9_6A87CA1A_1`; sole evidence `EVIDENCE_9d6c1fa00626430586d4709a619d6e30`.

## BUG-20260821-REVIEW-THREAD-ID-CONTRACT-MISMATCH

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Review command materialization and room-graph command schema binding
- Confirmed fact: After case `CASE_P9_6A88011D_1` completed Hearing and opened Review task `hearing-review-task-1a4fd910efa430db8699508aaa6de695`, system reviewer `reviewer-local` successfully moved the task from `PENDING` to `IN_REVIEW`. Submitting the canonical `APPROVE` decision then returned `INVALID_ARGUMENT` because `$.thread_id` did not match `^grt\\.v1\\.[0-9a-f]{32}$`.
- Root cause and evidence: `CanonicalTargetRoomCommandMaterializer.graph` emits the schema-compliant `grt.v1.<32 hex>` identifier only for `EVIDENCE`. Its non-Evidence branch emits `target-room-thread:<32 hex>`, so the Review command is rejected by `room-graph-command.schema.json` before command material, approval authority, or an Outcome projection can be persisted.
- Impact: The Review task remains `IN_REVIEW` with an empty decision payload, `human_review_record` contains no row for the task, and the Review/Outcome workflow remains on its seven-day decision wait timer. No Outcome operation has started.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.f4a86c1f7efc633de83cca0096f1b1e3`; case `CASE_P9_6A88011D_1`; Review workflow run `3deea9d2-fe1d-4a65-a6a9-0fc9254703d9`; source `CanonicalTargetRoomCommandMaterializer.java:368-370`; rejected request `REQ_ba8e8a6ca3414a7baf1ffa92b6e516f9`.

## BUG-20260821-HEARING-E1-FACT-LEVEL-EVIDENCE-REUSE-FORBIDDEN

- Severity: P1
- Status: FIXED_UAT_VERIFIED
- Component: Hearing E1 party-batch authorization under fact-level evidence coverage
- Confirmed fact: Fresh canonical case `CASE_P9_6A87C582_1` rejected the MERCHANT E1 batch when it reused the sole USER evidence. After the API boundary was relaxed, fresh case `CASE_P9_6A88011D_1` accepted and persisted both USER and MERCHANT E1 batches with the same sole fixture evidence `EVIDENCE_60159657eaa84dbaa5318fec655d8628`, but the E2 preparation activity repeatedly failed with `target Hearing supplemental evidence identity is invalid` before creating an E2 AgentRun.
- Root cause and evidence: The downstream `JdbcTargetHearingAgentStageInputFactory.partyBatches` retained two role-scoped assumptions that conflicted with fact-level reuse. A global `seenEvidenceIds` set rejected the same `evidence_id` in the second party batch, and `supplementalEvidence` additionally queried the evidence by the current batch participant's submitter ID and role. The current E1 request targets both `USER` and `MERCHANT`, while the canonical case intentionally contains one USER-submitted evidence item and the frozen fact-level coverage contract no longer requires a role-specific evidence copy. The focused Java-to-Python contract test passed after the factory was changed, and the recovered UAT E2 invocation persisted two completed party batches whose `source_refs` both contain `EVIDENCE_60159657eaa84dbaa5318fec655d8628` while materializing exactly one evidence body.
- Impact: Before the repair, case `CASE_P9_6A88011D_1` was stranded at `EVIDENCE_SYNTHESIZING` stage 9 and the Hearing workflow failed before creating E2. After recovery from the pre-E2 persisted boundary, E2, dossier freezing, Judge V1, jury review, and Judge V2 all completed and the Hearing flow reached `CLOSED` stage 15.
- Identifying metadata: observed and UAT-verified 2026-08-21; prior activation `p9act.v1.fb52f5e5294c13a84d2b0fd8869fa418`; current activation `p9act.v1.f4a86c1f7efc633de83cca0096f1b1e3`; current case `CASE_P9_6A88011D_1`; recovered Temporal run `7e5cb70f-8884-4536-a8ce-c01a1aa118ad`; E2 run `target-hearing-run:5186aa2b07fd3c73b76f02595941c378`; source `JdbcTargetHearingAgentStageInputFactory.java`.

## BUG-20260821-CANONICAL-EVIDENCE-V3-TERMINAL-SEQUENCE

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Canonical Evidence Clerk V3 stream/replay terminal contract
- Confirmed fact: Fresh single-evidence canonical case `CASE_P9_6A87BBB6_3` accepted the sole USER fixture evidence `EVIDENCE_6a92bfdcb5f74fa48c68a7a96e68745a` and its submission command, but the UAT failed while validating that submission run at `canonical_user_evidence_stream_replay/terminal_event_sequence`.
- Root cause and evidence: The Python Evidence V2 producer accepted a `BOUND` observation while emitting its same-evidence `EVIDENCE_ASSESSMENT` with an empty `observation_slots` list. The Java formal commit correctly requires every bound observation slot to be attached to exactly one evidence assessment and rejected the result with `bound Evidence observation has no assessment attachment`. Run `target-evidence-run:252b49b84d013e2ca750407e1d614d6f` therefore persisted the otherwise complete V3 sequence through `usage/final` and then the non-success `error` event at sequence 18, leaving the run `ABORTED/AGENT_RUN_FINALIZATION_REJECTED`.
- Impact: The clean canonical backend UAT cannot yet prove the sole evidence submission, evidence completion, or downstream Hearing/Review/Outcome chain.
- Verification evidence: The Evidence V2 workflow regression suite passed all 14 tests, including deterministic observation-slot derivation and byte-equal live/terminal assessment headers. Fresh activation `p9act.v1.fb52f5e5294c13a84d2b0fd8869fa418` then completed the sole evidence submission run, both party completion boundaries, Hearing answer bundles, and M2 freeze for case `CASE_P9_6A87C582_1` without a terminal-sequence error.
- Regression fact: Fresh case `CASE_P9_6A87D8B7_1` completed both parties' Intake, uploaded and accepted the sole canonical evidence `EVIDENCE_1cc93e76d5a44ae58eea6a9eb3497a65`, and then failed the same UAT terminal-event-sequence check while replaying the submitted Evidence run after 30.594 seconds. No Hearing action was attempted after this failure.
- Regression root cause and evidence: Graph command `evidence-submit:EVIDENCE_BATCH_2187207de0e944c187693e787f6acf3b` completed and emitted its `final` event. Its immutable proposal contains `MATERIAL_RECEIPT`, `EVIDENCE_OBSERVATION`, and `EVIDENCE_ASSESSMENT` frames but no `ROOM_READINESS` frame; `observation_graph`, `evidence_assessments`, and `evidence_requests` each exactly equal their frame-manifest projections, while top-level `room_readiness` defaults to an empty object with no corresponding frame. The current Python V3 stream contract no longer requires a terminal readiness frame and therefore accepted this provider output, but Java `FinalizeAgentRunActivity` still requires a `ROOM_READINESS` frame whose header equals that projection. It rejected formal commit with non-retryable `AgentRunFinalizationRejected`: `Evidence V2 derived projections differ from the frame manifest`. Agent run `target-evidence-run:6ce90d79bc4834629f7f5f031eb9df45` was consequently marked `ABORTED/UNCOMMITTED` and appended `AGENT_RUN_FINALIZATION_REJECTED` after `final`, producing the observed double-terminal sequence.
- Reproduction sampling: Three consecutive independent model calls using the exact persisted `evidence_turn_request` from the failed command all returned HTTP 200 and the same structurally valid frame order `MATERIAL_RECEIPT -> EVIDENCE_OBSERVATION -> EVIDENCE_ASSESSMENT -> EVIDENCE_REQUEST -> ROOM_READINESS`. In all three samples the readiness frame occurred exactly once at the end, sequences were contiguous, and all four derived projections matched their manifest frames. Durations were 41.959s, 31.258s, and 32.537s. The omission did not reproduce in the three-call sample, while the persisted failed call proves that the currently permissive provider contract can admit it intermittently.
- Latest focused verification evidence: Python Evidence V3 workflow checks passed 3/3 for missing-readiness empty projection, ordinary complete generation, and out-of-scope fact rejection. Java `TargetEvidenceTurnResultV2Test` passed 5/5 for ordinary projection/replay, empty readiness binding/replay, non-empty readiness without a source frame rejection, receipt authority, and fact scope. Java build completed successfully and `git diff --check` reported no whitespace errors.
- Identifying metadata: observed 2026-08-21; latest activation `p9act.v1.8b7d17085476ae4620999fe646e807cd`; fixture `air-purifier-specification-mismatch-v1`; latest case `CASE_P9_6A87D8B7_1`; failure stage `canonical_user_evidence_stream_replay`.

## BUG-20260821-CANONICAL-UAT-DUPLICATES-FIXTURE-EVIDENCE

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Canonical full-chain UAT evidence submission sequence
- Confirmed fact: Fresh canonical cases `CASE_P9_6A87BBB6_1` and `CASE_P9_6A87BBB6_2` each uploaded two evidence items, one as USER and one as MERCHANT, even though fixture `air-purifier-specification-mismatch-v1` authorizes one fixed USER text evidence submission.
- Root cause and evidence: `run-dynamic-five-round-uat.py` replaces the base runner's upload callback with the canonical fixture uploader, but the base five-round runner still invokes that callback once for the initiator path and again for the respondent path. The two runs created evidence pairs `EVIDENCE_6d46b65d84db4de6978f88d78da8b738`/`EVIDENCE_7685a01d0b5a489a97f939daa5f7fe30` and `EVIDENCE_d128355413d34cf0b6421b1fd9ffa240`/`EVIDENCE_60ceeb2d0b674ddf80d6bbb556d94578` from the same immutable fixture content.
- Impact: The UAT no longer represents the frozen canonical business sequence and can overstate evidence coverage by submitting a duplicate fixture under the opposing party.
- Verification evidence: Three focused canonical-runner regressions passed for the USER-only submitter policy, exact-one catalog acceptance, and duplicate catalog rejection. Fresh case `CASE_P9_6A87C582_1` then durably submitted exactly one fixture evidence `EVIDENCE_399fb977dc9b4e3e9425bbd41ad310e2`; MERCHANT completed without an upload, and the exact-one catalog guard passed both before party completion and after M2 freeze.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.7e0d0cb14f82f915c2eff536a29cbd40`; fixture `air-purifier-specification-mismatch-v1`.

## BUG-20260821-HEARING-PROJECTION-FAILS-AFTER-REVIEW-HANDOFF

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Hearing read projection after handoff to human Review
- Confirmed fact: Fresh canonical case `CASE_P9_6A87AF85_1` durably completed E2, dossier freezing, Judge V1, jury review, Judge V2, and created exactly one pending ReviewTask, but `GET /api/disputes/CASE_P9_6A87AF85_1/hearing` returns HTTP 500 after the case reaches `WAITING_HUMAN_REVIEW/AWAIT_REVIEW`.
- Root cause and evidence: The V2-to-Review handoff inserts `remedy_plan.source_route='HEARING_V2'`, while `RemedyPlanEntity.sourceRoute` is persisted as the `RouteType` enum, whose only valid values are `TRANSFERRED`, `SIMPLE_HEARING`, and `FULL_HEARING`. The Hearing projection's Review-gate lookup reads the newest remedy plan; Hibernate therefore calls `RouteType.valueOf("HEARING_V2")` and Spring reports `InvalidDataAccessApiUsageException: No enum constant com.example.dispute.domain.model.RouteType.HEARING_V2`. The same handoff authority is already restricted to `FULL_HEARING`, so the persisted value contradicts its authoritative route.
- Impact: Parties and the UAT client cannot retrieve the final Hearing projection or confirm the decision chain after Review handoff, although the human Review task is durable and discoverable by the platform reviewer.
- Verification evidence: The focused producer and staged V074-to-V075 migration regressions passed together (`16` tests, no failures). Source runtime activation `p9act.v1.7e0d0cb14f82f915c2eff536a29cbd40` then applied V074 and V075 to the retained database; the exact previously failing `GET /api/disputes/CASE_P9_6A87AF85_1/hearing` request now returns HTTP 200.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.1d3e482ff88629214d4bf2b4e50d2546`; latest reproduced request `REQ_99adc03c8cb541c489b748d80f64b5ca`; trace `TRACE_59572f814ced18fa4e3993e6ec8ecac7`; task `hearing-review-task-a7034c96d8323a3f943b3c141e3efce3`.

## P0-20260821-INTAKE-COMMAND-ROUTING-LEASE-LOSS

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Intake command routing and Graph execution lease
- Confirmed fact: Fresh canonical case `CASE_P9_6A87AA27_1` durably accepted USER Intake opening run `target-intake-run:fe6b592447c834f19fae85753f0314c6`, but the run ended before its first formal turn committed; the public UAT stopped at `initiator_stream_1/agent_run_error` after 91.094 seconds.
- Root cause and evidence: The control worker's first `RecordCaseCommandRouted` activity used deterministic Temporal route time `2026-08-21T09:35:05.140+08:00`, while the command row was durably accepted about 14 milliseconds later at `2026-08-21T09:35:05.153750+08:00`. `markOrchestrationAccepted` copied the earlier route time into `orchestrated_at` and `updated_at`, so PostgreSQL rejected `orchestrated_at < accepted_at` with `ck_case_command_status_times`. During the same logical run, the Python Graph stream later lost its renewal authority at `app.graph_runtime.lease:renew:362`; reconcile returned HTTP 409 and no public output or final frame had been committed.
- Impact: The first USER Intake turn cannot complete, so the fresh-case backend UAT cannot reach Evidence, Hearing, Review, or execution.
- Verification evidence: The PostgreSQL integration regression reproduced the observed 14-millisecond ordering failure against `ck_case_command_status_times`, then passed after routing lifecycle time was bounded by the durable command acceptance time. The exact regression, Temporal replay, Shadow two-phase completion, and microsecond deadline-boundary nodes passed together (`4` focused tests, no failures).
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.cc7d114640e5125399601d8f3ee2c8fd`; command `intake-message:fe6b592447c834f19fae85753f0314c6`; command row `CMD_ec6cf997aff647539b7f1e6a0158fc29`; actor `five-round-uat-user-4d8df70de19d40148e398f16ba69dd45`.

## P0-20260821-REVIEW-ROOM-PROVISIONING-FAILURE

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Review room Temporal provisioning
- Confirmed fact: Case `CASE_P9_6A878C2A_1` completed Hearing CLOSE at process revision `29`, durably created the Review epoch and exact ReviewTask binding at the same revision, and then moved the Review epoch to `PROVISIONING_FAILED/FAILED` before a Review room run ID was bound.
- Root cause and evidence: The Hearing-to-Review producer invoked the room transition without its supported sequence authority. The resulting Review bootstrap payload reused the stale database projection boundary `last_command_sequence=11` and `last_case_event_sequence=22`, while the durable ledgers and the parent CaseProcess had both advanced to command `15` and event `68` (`next=16/69`); the parent therefore rejected `first=12/23` with `ROOM_EPOCH_SEQUENCE_BOUNDARY_CONFLICT`.
- Verification evidence: The producer contract test confirms the Hearing-to-Review transition carries the exact persisted command/event pair; the allocator integration test advances `11/22` to `15/68`, replays the same Review epoch idempotently, and rejects a drifted `15/69`; the three adjacent close atomicity/replay tests and all 14 Hearing formalization tests pass (`18` distinct focused tests, no failures).
- Impact: The frozen dossier, V1, jury opinion, V2 draft, ReviewTask, and Hearing close remain durable, but the case cannot enter active human Review until the failed provisioning is understood.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A878C2A_1`; Hearing reset run `d3884b6d-95cb-4643-bbb8-35570ff22016`; Review epoch `CRE_e17b74cfbeb1480ba3e913368d23a233`.

## P0-20260821-HEARING-CLOSE-SUCCESSOR-AUTHORITY-VALIDATION

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Atomic Hearing CLOSE successor-authority verification
- Confirmed fact: With V073 active, `CloseTargetHearing` passes the Review epoch task-binding insert and then fails in `JdbcHearingAuthorityLedger.requireSuccessorRoomAuthority` with `the close receipt did not create the exact Review room authority`.
- Root cause and evidence: A debugger inspection inside the uncommitted CLOSE transaction found the exact successor tuple at process revision `29`: the source Hearing epoch was terminal at room revision `17`, while the new Temporal Review epoch was legitimately `PREPARING/PENDING` at room epoch `0`; the verifier admitted only later provisioning or active states and therefore rejected the allocator's first durable successor state.
- Impact: The close-to-Review transaction remains fail-closed and resumable, but Hearing cannot complete until the verifier is aligned with the allocator's actual durable successor coordinates.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A878C2A_1`; Hearing reset run `f705bab8-42c0-47fd-94b3-9af039e953ed`; failing activity `CloseTargetHearing`.
- Verification evidence: `HearingTemporalLedgerIntegrationTest` passed atomic close-to-Review creation, incomplete-close rollback, exact replay, stale-revision rejection, and adjacent ordinary-stage coverage (3/3 selected nodes); `JdbcTargetHearingFormalizationActivitiesTest` passed 13/13 focused nodes.

## P0-20260821-REVIEW-EPOCH-TASK-BINDING-ZERO-EPOCH-CONSTRAINT

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Review epoch coordinates across persistence and Temporal Outcome provisioning
- Confirmed fact: The database binding constraint was repaired to accept the allocated first Review room epoch `room_epoch=0`, but fresh canonical case `CASE_P9_6A87AF85_1` still persisted its Review epoch as `PROVISIONING_FAILED/FAILED` before a Review child run ID was bound.
- Root cause and evidence: Review epochs use the same zero-based room coordinate contract as Intake, Evidence, and Hearing. PostgreSQL now accepts `room_epoch=0`, while `TargetTypedRoomCaseProcessDispatcher.startOutcome` still invokes a Review-only positive-epoch guard. The control worker rejected the durable Review epoch with `room epoch provisioning failed: REVIEW target room epoch must be positive` and recorded `ROOM_EPOCH_PROVISIONING_RUNTIME_FAILURE`.
- Impact: The frozen dossier, Judge V1, jury report, Judge V2, handoff, and one pending ReviewTask are durable, but the Review Temporal room cannot become active and no human decision can be routed through it.
- Verification evidence: The dispatcher, Outcome kernel, authorization view, terminal lookup, non-execution branch, evaluation readers, synthetic downstream path, replay/timer/reliability harnesses, and PostgreSQL constraints now share the zero-based contract. The 11 focused suites report 107 passing tests; the clean Flyway integration applies through V074 and proves epoch `0` is accepted while `-1` is rejected at every newly aligned database boundary.
- Identifying metadata: observed 2026-08-21; cases `CASE_P9_6A878C2A_1` and `CASE_P9_6A87AF85_1`; Review epoch `CRE_8e0b228f7216412bb9a8d78ab94bd65b`; failing workflow `case-process:legacy-default:CASE_P9_6A87AF85_1`.

## BUG-20260821-HEARING-LEDGER-LEGACY-ACTION-TEST-FIXTURE

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Hearing temporal ledger integration test fixture
- Confirmed fact: `HearingTemporalLedgerIntegrationTest.formalPartyActionUsesTheAuthorityReceiptAndFailsClosedBeforeAnyFact` fails while constructing its action command with `schemaVersion is not valid for actionType`.
- Root cause and evidence: The test still supplies the retired `hearing_answer_bundle.v1` action schema while the current formal answer contract accepts the V4 answer bundle authority.
- Impact: The whole ledger integration test class cannot be used as one green verification unit, although the current Target Hearing close path does not execute this fixture.
- Identifying metadata: observed 2026-08-21; test `HearingTemporalLedgerIntegrationTest#formalPartyActionUsesTheAuthorityReceiptAndFailsClosedBeforeAnyFact`.

## BUG-20260821-SIMULATED-CASE-PURGE-APPEND-ONLY-ISSUE-STATE

- Severity: P1
- Status: FIXED_UAT_VERIFIED
- Component: Reviewer-authorized simulated case purge
- Confirmed fact: `HearingTemporalLedgerIntegrationTest.reviewerAuthorizedDemoPurgeRemovesTheAdditiveHearingAuthorityRows` fails because `purge_simulated_dispute_case` raises `hearing_issue_state_set is append-only`.
- Root cause and evidence: Purging the parent `hearing_flow_instance` cascades a delete into `hearing_issue_state_set`, whose append-only database trigger rejects that mutation.
- Impact: Reviewer cleanup of simulated cases that contain a persisted Hearing issue-state set cannot complete; this does not mutate or block the active Review handoff case.
- Identifying metadata: observed 2026-08-21; database function `purge_simulated_dispute_case`; table `hearing_issue_state_set`.

## P0-20260821-HEARING-REVIEW-EPOCH-ALLOCATION-INVARIANT

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing close to Review epoch allocation
- Confirmed fact: After the monotonic terminal-time repair, reset Hearing run `1b184fab-5eb5-4272-86aa-293839fbfefe` passes the prior terminal-time guard and reaches Review epoch allocation, but every `CloseTargetHearing` attempt fails with `Review epoch allocation`.
- Root cause and evidence: The durable CLOSE receipt advanced the Hearing authority from process/room revision `28/16` to `29/17`, then the ordinary room allocator treated the same close-to-Review handoff as another global transition and allocated Review at process revision `30`. The post-allocation invariant requires the Review epoch to share the CLOSE receipt's process revision `29`, so the surrounding transaction rolled back.
- Impact: The durable V2 handoff, frozen ReviewPacket, and single ReviewTask remain intact, but the Hearing Workflow cannot close or bind an active Review epoch.
- Verification evidence: `HearingTemporalLedgerIntegrationTest` passed the incomplete-close rollback, single-revision close-to-Review transition, exact replay, and adjacent ordinary-stage cases (3/3 focused nodes); `JdbcTargetHearingFormalizationActivitiesTest` passed 13/13 focused nodes.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A878C2A_1`; reset Hearing run `1b184fab-5eb5-4272-86aa-293839fbfefe`; failing activity `CloseTargetHearing`.

## P0-20260821-HEARING-REVIEW-CLOSE-TERMINAL-TIME-REGRESSION

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing to Review terminal transition
- Confirmed fact: Canonical case `CASE_P9_6A878C2A_1` successfully committed the post-V2 handoff, version 2 adjudication projection, frozen ReviewPacket, and pending ReviewTask, then every `CloseTargetHearing` attempt failed with `terminal time cannot move backward`.
- Root cause and evidence: The close Activity supplies a terminal timestamp that precedes the already committed Hearing handoff boundary. The room-epoch transition rejects that regressing time before the Hearing Workflow can close and bind the Review epoch.
- Impact: Human review data is durable and queryable, but the Hearing Workflow remains running at `HUMAN_REVIEW_OPEN` and cannot complete the formal room transition to Review.
- Verification evidence: The formal handoff and closure timestamps are now derived from the exactly fenced persisted Hearing epoch boundary, with closure choosing the later of that boundary and the durable handoff receipt. `JdbcTargetHearingFormalizationActivitiesTest` passed 13 tests with zero failures, including older, newer, equal, and missing-boundary cases.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A878C2A_1`; reset Hearing run `d17ae52d-1d97-42dc-8602-5e3da8f79d27`; failing activity `CloseTargetHearing`.

## P0-20260821-HEARING-REVIEW-HANDOFF-OPERATION-KEY-MISMATCH

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing V2 formal handoff command authority
- Confirmed fact: After resetting canonical case `CASE_P9_6A878C2A_1` to the post-V2/pre-handoff Temporal checkpoint on the repaired build, `HandoffTargetHearing` advanced beyond case-route validation but every attempt failed before commit with `operation key is not exact for the formal command`.
- Root cause and evidence: `HearingRoomWorkflowImpl` emits the current v2 handoff key that binds the durable Hearing epoch and Judge V2 artifact, while `HearingFormalFinalizer.HandoffCommand` still required the earlier key formula that bound only the Judge V2 artifact. The two producers implemented different formulas, and the formal command rejected every current Workflow key before commit.
- Impact: The formal V2 artifact remains frozen at `HUMAN_REVIEW_OPEN`; no handoff receipt, ReviewTask, Review epoch, or execution action can be committed.
- Verification evidence: The formal domain protocol now owns the epoch-bound v2 formula and the Workflow helper delegates to that exact implementation. The focused contract, exact Workflow history-replay, and Target formalization checks passed 19 tests with zero failures; the legacy non-epoch key remains rejected.
- Identifying metadata: observed 2026-08-21; original activation `p9act.v1.1387c1548b1dcbf0f1697cc28db5b4be`; case `CASE_P9_6A878C2A_1`; reset Hearing run `36115115-369e-4e87-8281-3cd0efef8a5b`; failing activity `HandoffTargetHearing`.

## P0-20260821-HEARING-REVIEW-CASE-ROUTE-MISSING

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing V2 ReviewPacket case-summary authority
- Confirmed fact: Fresh canonical case `CASE_P9_6A878C2A_1` completed M2, E1, E2, dossier freezing, Judge V1, jury review, and Judge V2, then every `HandoffTargetHearing` attempt failed with `formal Hearing authority omits case route` before any adjudication projection or ReviewTask was committed.
- Root cause and evidence: The Target-only Evidence terminal writer moved `fulfillment_dispute_case` to `case_status=HEARING_OPEN` and `current_room=HEARING` with direct SQL but omitted the `hearing_route=FULL_HEARING` write performed by the ordinary domain transition. The post-V2 handoff therefore received an otherwise exact Temporal Target Hearing authority with a null case route.
- Impact: The formal V2 artifact is frozen at `HUMAN_REVIEW_OPEN`, but the flow cannot create the version 2 review projection, remedy plan, ReviewPacket, ReviewTask, Review epoch, or execution action.
- Verification evidence: The Target Evidence transition now persists `FULL_HEARING` in the same write that opens Hearing. In-flight reconciliation is restricted to an exact active/ready Temporal Hearing projection and epoch with matching flow, revision, fencing token, and `TARGET_E2E_CANDIDATE` binding; the Review projection rejects null and non-`FULL_HEARING` routes. The two focused suites passed 34 tests with zero failures.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.1387c1548b1dcbf0f1697cc28db5b4be`; case `CASE_P9_6A878C2A_1`; Hearing stage sequence 14; failing activity `HandoffTargetHearing`.

## P0-20260821-HEARING-REVIEW-HANDOFF-RUN-ROLE-MISMATCH

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing formal V2 to Review handoff authority
- Confirmed fact: Fresh canonical case `CASE_P9_6A878272_3` committed distinct V1, jury, and V2 artifacts with completed stage rows and completed AgentRuns, then every `HandoffTargetHearing` attempt returned `target Hearing row is absent or ambiguous` before creating a ReviewTask.
- Root cause and evidence: `JdbcTargetHearingFormalizationActivities.ensureReviewProjection` joins the three artifact-bound AgentRuns using semantic persona roles `PRESIDING_JUDGE` and `JURY_PANEL`. The persisted target AgentRuns for the exact V1, jury, and V2 artifact IDs all have `agent_role=SYSTEM`, while the authoritative stage rows carry the semantic processor roles and bind byte-equal stage outputs to those AgentRuns. The role predicates therefore eliminate the otherwise exact authority row at line 1068.
- Impact: The Target Hearing workflow remains at `HUMAN_REVIEW_OPEN`; it cannot materialize the review projection, remedy plan, ReviewPacket, ReviewTask, Review epoch handoff, or deterministic execution.
- Verification evidence: The Target Review authority now binds semantic personas through the completed Hearing stage rows while requiring the artifact-bound Temporal AgentRuns to carry their protocol-defined `SYSTEM` execution role, `agent-stream.v3` protocol, `TEMPORAL_ACTIVITY` executor, and `HEARING` room. The focused handoff/formalization/scheduler suite passed 20 tests with zero failures.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.5fc74abbff2e4b91d24fa01d3a88be42`; case `CASE_P9_6A878272_3`; V1 run `target-hearing-run:b6f37269eb5c3e929f0b6a301b0820f6`; jury run `target-hearing-run:3e524aaa241a30e3a66dc1beb26f60f3`; V2 run `target-hearing-run:fccdae71b7b63d9ea8c2e3ab266d3485`.

## P0-20260821-HEARING-V2-REVIEW-PROJECTION-MISSING

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing V2 to human-review handoff
- Confirmed fact: Fresh canonical case `CASE_P9_6A878272_3` froze all three decision-chain artifacts and reached `HUMAN_REVIEW_OPEN`, but no ReviewTask was created and the handoff recovery scheduler failed every 30 seconds.
- Root cause and evidence: The Target handoff projection used a generated `review-draft` ID at draft version 1 instead of the frozen V2 artifact ID at version 2, while the legacy recovery scheduler also scanned Temporal-owned V2 artifacts and required an exact V2 projection before the Target handoff had run. This produced `V2 adjudication projection is required` at `HearingReviewHandoffService.java:93` and left the formal artifact and Review projection on different identities.
- Impact: The flow remains at `HUMAN_REVIEW_OPEN` with V1, jury report, and V2 frozen, but cannot create a remedy plan, ReviewPacket, ReviewTask, or reach deterministic execution.
- Verification evidence: Target handoff now materializes and exact-replays one version 2 adjudication projection using the frozen V2 artifact ID, V2 AgentRun ID, and Hearing state; the legacy recovery repository now selects only artifacts whose persisted projection and epoch both declare `LEGACY` ownership. The focused handoff/formalization/scheduler suite passed 20 tests with zero failures.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.5fc74abbff2e4b91d24fa01d3a88be42`; case `CASE_P9_6A878272_3`; Hearing stage sequence 14; decision-chain keys `JUDGE_PROPOSAL`, `JURY_REVIEW_REPORT`, `ADJUDICATION_DRAFT`.

## P0-20260821-HEARING-E1-SECOND-PARTY-BATCH-REJECTED

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing E1 continuation UAT reconciliation
- Confirmed fact: Fresh canonical case `CASE_P9_6A878272_3` froze M2, generated E1, and accepted the USER hearing evidence batch, but the immediately following MERCHANT batch did not return the accepted status required by the public UAT contract.
- Root cause and evidence: The continuation helper did not reconcile against stable Hearing authority. It first treated a non-200 MERCHANT response as terminal unless it matched one legacy Evidence-room envelope; the authoritative projection still showed USER `SUBMITTED` and MERCHANT `PENDING`, and the same MERCHANT payload then returned 200. On resume, the helper also rejected the valid current stage name `JURY_REVIEWING` because it used a retired downstream-stage name whitelist even though stage sequence had advanced beyond E1 and the V1 artifact was frozen.
- Impact: E1 cannot collect both parties' bound batches, so E2 synthesis, unified dossier freezing, Judge V1, jury review, Judge V2, human review, and execution cannot proceed.
- Verification evidence: The continuation helpers compile after adding bounded 409 reconciliation, skipping roles already marked `SUBMITTED`, and using the stable E1 stage sequence boundary (`8`) instead of downstream display-name whitelists. Case `CASE_P9_6A878272_3` has both E1 batches, crossed E2 and dossier freezing, froze V1, and reached jury review without any accepted action being resent.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.5fc74abbff2e4b91d24fa01d3a88be42`; case `CASE_P9_6A878272_3`; USER evidence `EVIDENCE_3569a8d773a24b3ea528b97494e22237`; MERCHANT evidence `EVIDENCE_93c30d1e46864056b77940ff8fe29086`.

## BUG-20260821-CANONICAL-UAT-RESPONDENT-DOSSIER-VERSION

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Canonical full-chain UAT respondent Intake checkpoint
- Confirmed fact: Fresh canonical case `CASE_P9_6A878272_1` committed the MERCHANT opening as formal turn 1 with `case_intake_dossier.dossier_version=3`, but `.local-dev/run-dynamic-five-round-uat.py` continued polling `respondent_formal_1` and never advanced to the second MERCHANT statement.
- Root cause and evidence: The maintained runner assumes one exact respondent dossier coordinate. The current authoritative transition can either carry the room-open revision forward or persist it separately: case `CASE_P9_6A878272_1` validly committed respondent formal turn 1 at dossier version 3, while fresh case `CASE_P9_6A878272_2` validly committed the same stage at version 4. The first wrapper condition waited forever on version 3; a focused decrement then rejected version 4 even though run, message, process-readiness, and matrix-chain checks had passed.
- Impact: The canonical UAT driver stalls after a successful respondent opening and cannot exercise Evidence or Hearing even though the backend is ready for the next statement.
- Verification evidence: The adapter compiles and its focused branch check admits only the adjacent coordinates `(3,4)` for legacy expectation 4 and `(4,5)` for legacy expectation 5. The fallback is limited to the exact `dossier_version` check; all run identity, formal message, process-readiness, source-turn, and matrix successor validations remain enforced.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.5fc74abbff2e4b91d24fa01d3a88be42`; cases `CASE_P9_6A878272_1` and `CASE_P9_6A878272_2`; both runners stopped before any duplicate submission.

## P0-20260821-HEARING-JUDGE-V2-REVIEW-COVERAGE

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing Judge V2 review-response generation
- Confirmed fact: A read-only integrated model run over canonical frozen dossier `hearing-dossier-fd1559a768bb366b9dada4680fa08e72` produced a valid Judge V1 result and valid six-dimension jury review, but Judge V2 failed before result materialization because its response set omitted all three V1 review-focus refs while covering all six jury findings and three mandatory revisions.
- Root cause and evidence: The V2 model context nests V1 review-focus items under `v1_draft_pack.review_focus` while jury review items are supplied through the separate `jury_opinion_pack`. The returned output treated only the jury-side items as the required response checklist. `_validate_review_responses` then rejected the exact missing set `V1_FOCUS_01`, `V1_FOCUS_02`, and `V1_FOCUS_03`, with no extra refs.
- Impact: Judge V2 cannot produce or commit `hearing_judge_v2.v2`; the case cannot reach the human-review handoff even though the frozen adjudication authority, V1 draft, and jury review are valid.
- Verification evidence: A repeated read-only integrated model chain over the same frozen dossier produced exactly 12 bound review responses for 3 V1 focus items, 6 jury findings, and 3 mandatory revisions; `hearing_judge_v2.v2` contained all 9 fact findings, both rule applications, 4 remaining human-attention items, a valid result hash, and `is_final_decision=false`.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A87787C_1`; V1 review-focus count 3; jury findings count 6; mandatory-revision count 3.

## P0-20260821-HEARING-JUDGE-V1-SCHEMA-REPAIR

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing Judge V1 generation
- Confirmed fact: Canonical case `CASE_P9_6A87787C_1` successfully froze `trial_dossier.v2` and entered `JUDGE_V1_GENERATING`, but AgentRun `target-hearing-run:2621340afa9538448669d90284eced86` ended `FAILED/UNCOMMITTED` after both permitted provider calls failed structured-output validation.
- Root cause and evidence: A read-only replay of the exact frozen Judge V1 authority produced the complete top-level shape, all 9 fact findings, and both frozen rule applications, but 6 findings with empty `evidence_ids` also returned `evidence_gap=null`. `HearingJudgeFactFinding` enforces the cross-field rule that an evidence-free finding requires a non-null gap, while that Pydantic model validator is absent from the provider JSON Schema supplied to both the strict call and schema-repair call. Both calls can therefore satisfy the advertised schema yet fail the hidden parser rule, ending as `AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED` before formal V1 commit.
- Impact: No `judge_proposal.v2` exists; jury review and Judge V2 cannot start, while the read model remains at `JUDGE_V1_GENERATING` with no half-written decision artifact.
- Verification evidence: Focused V1/V2 workflow tests pass, and repeated read-only model execution over the exact frozen canonical dossier produced `hearing_judge_v1.v2` with all 9 fact findings, both frozen rule applications, 3 review-focus items, 6 explicit evidence gaps, and a valid proposal hash.
- Latest regression fact: Fresh case `CASE_P9_6A8B9CE8_16` completed both Intake actors, Evidence submission and completion, Hearing questions, both party answer bundles, supplemental evidence requests, both supplemental evidence batches, evidence synthesis, and dossier freezing. Judge V1 run `target-hearing-run:9210869482523929b707b6b00c4b7803` then ended `FAILED / UNCOMMITTED` with public diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`; the durable Hearing stage remains `JUDGE_V1_GENERATING` and no V1 artifact committed.
- Latest root cause and evidence: The failed V1 provider payload omitted 6 of the 7 fact findings required by that frozen M2. Judge V1 and Judge V2 previously ran full adjudication coverage validation only after `HarnessModelRunner` returned, so semantic-final-validation regeneration could not observe this missing-M2 failure and the logical run terminated immediately. Both judge stages now pass their full adjudication validator into the model-runner semantic-validation boundary. Focused V1/V2 and model-runner regeneration tests pass; a read-only execution of the exact failed `_16` frozen V1 input on the repaired backend completed in 38.219 seconds with all 7 frozen fact IDs, no missing or extra IDs, and a valid `hearing_judge_v1.v2` proposal.
- Identifying metadata: observed 2026-08-21; dossier `hearing-dossier-fd1559a768bb366b9dada4680fa08e72`; flow `ROOM_HEARING_1dd2f2ba3d7a34f7819883f57790`; failed run completed 2026-08-21T06:10:45+08:00.

## P0-20260821-HEARING-DOSSIER-V2-SOURCE-VALIDATION

- Severity: P0
- Status: FIXED_UAT_VERIFIED
- Component: Target Hearing dossier persistence validation
- Confirmed fact: Fresh canonical case `CASE_P9_6A8772A0_1` completed E2 and persisted `fact_evidence_matrix.v3`, then `FreezeTargetHearingDossier` repeatedly failed at `JdbcHearingFormalFinalizer.requireDossierSources` with `formal Hearing persistence validation failed closed`.
- Root cause and evidence: The `trial_dossier.v2` producer now freezes the M2 fact matrix, fact-level E2 evidence matrix, and adjudication rules, while `requireDossierSources` still dereferences retired dossier payload fields `question_set`, `answer_bundles`, `evidence_request_set`, and `evidence_batches`. Its exact-row query therefore returns zero for a valid V2 payload.
- Impact: The workflow remains active at `DOSSIER_FREEZING`; no `trial_dossier.v2` row, Judge V1, jury review, or Judge V2 artifact can be committed.
- Verification evidence: Fresh canonical case `CASE_P9_6A87787C_1` crossed `DOSSIER_FREEZING` and committed `trial_dossier.v2` id `hearing-dossier-fd1559a768bb366b9dada4680fa08e72` with content hash `341d406689e5f9ee83a6857441df2ba606c84ad668ba1542266d39345b830b5b`.
- Identifying metadata: observed 2026-08-21; flow `ROOM_HEARING_8e1552fe5e07c23926ffe2031089`; E2 run `target-hearing-run:215392fea423307c93a4a086d0d79381`; failing activity `FreezeTargetHearingDossier`.

## P0-20260821-HEARING-DOSSIER-FINALIZE-KEY

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Hearing dossier freezing
- Confirmed fact: `HearingRoomWorkflowImpl` supplies the DOSSIER_FREEZING stage-completion operation key to `freezeDossier`.
- Root cause and evidence: `JdbcTargetHearingFormalizationActivities.freezeDossier` reuses that `hearing.stage:*` key when constructing a `FINALIZE` authority commit, while `HearingAuthorityCommit` and `HearingFormalFinalizer.DossierCommand` require an exact `hearing.finalize:*:<stage-sequence>:trial_dossier.v2:<request-hash>` key.
- Impact: The Target Hearing workflow cannot construct the formal dossier-freeze command and therefore cannot advance from DOSSIER_FREEZING to JUDGE_V1_GENERATING.
- Verification evidence: The exact `trial_dossier.v2` finalize key, frozen-source replay coordinates, formal dossier command, and downstream decision-chain contracts passed 27 focused Java tests; Flyway applied all 81 migrations through V072 on PostgreSQL 16.
- Identifying metadata: observed 2026-08-21; source paths `HearingRoomWorkflowImpl.java`, `JdbcTargetHearingFormalizationActivities.java`, `HearingAuthorityCommit.java`, `HearingFormalFinalizer.java`.

## BUG-20260821-EVIDENCE-FIRST-OUTPUT-LATENCY

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Evidence submission streaming
- Confirmed fact: Two canonical fresh-case UAT runs exceeded the configured Evidence first-public-output threshold: a USER evidence submission reached its first persisted public utterance after 40.219 seconds, and a later Evidence opening reached it after 24.891 seconds.
- Root cause and evidence: Root cause is not yet isolated; the failures were reported at `evidence_submission_stream_replay/first_room_utterance_threshold_seconds` for case `CASE_P9_6A8766EC_1` after the evidence upload and submission had durably completed, and at `evidence_opening_stream_replay/first_room_utterance_threshold_seconds` for case `CASE_P9_6A88011D_1` after the opening run had durably completed.
- Impact: The end-to-end functional chain remains resumable from each accepted Evidence boundary, but the evidence-room first-output performance SLO is not met in these runs.
- Identifying metadata: observed 2026-08-21; evidence ID `EVIDENCE_115abe4d4ea64a2eac484630d37b5ee7` for the submission run; opening run `target-evidence-run:c5f2052c48e93afe83e9d1bad6d62c8a`; UAT harness `.local-dev/run-dynamic-five-round-uat.py`.

## BUG-20260821-EVIDENCE-COMPLETION-REPLAY-PROJECTION-DRIFT

- Severity: P2
- Status: FIX_IMPLEMENTED_FOCUSED_CHECK_PASSED_UAT_PENDING
- Component: Canonical backend UAT Evidence completion replay assertion
- Confirmed fact: Case `CASE_P9_6A88011D_1` accepted both party completion actions, sealed Evidence, started the first Hearing agent run, and then the continuation harness failed because the MERCHANT completion replay response was not structurally equal to the response captured before the room transition.
- Root cause and evidence: `.local-dev/continue-case3-uat.py` captures the first MERCHANT completion response, waits until the all-parties-completed transition is observable, then replays the same idempotency key and compares the two complete response projections with `==`; the later response was observed after the case had already advanced into Hearing.
- Impact: The accepted completion and Hearing transition remain durable, but the harness exits before submitting Hearing answers and must resume from the Hearing checkpoint.
- Identifying metadata: observed 2026-08-21; accepted evidence run `target-evidence-run:9d330c0242b63c51bfa2f1501140ea51`; first Hearing run `target-hearing-run:a5d2e1755d9639f09e2e4d19a627f3f4`; failed assertion `respondent_evidence_complete_replay/exact_replay`.

## BUG-20260821-HEARING-ANSWER-REVISION-RACE-RETURNS-500

- Severity: P1
- Status: REOPENED / UAT_FAILED
- Component: Hearing answer-bundle concurrent process-revision handling
- Confirmed fact: Case `CASE_P9_6A88011D_1` accepted and applied the USER V4 answer bundle, while the immediately following MERCHANT answer first returned retryable HTTP 409 `expected process revision is already reserved by an active command` and then returned HTTP 500 on the retry; no MERCHANT answer action or command was persisted.
- Root cause and evidence: The retry overlapped the USER answer command's optimistic state update. Java API logged `ObjectOptimisticLockingFailureException` for the request at 15:53:24.459, but the global exception boundary returned generic `INTERNAL_ERROR` instead of the existing fail-closed revision-conflict response. The USER command later reached `APPLIED`, and the durable action ledger contains only the USER `ANSWER_BUNDLE`.
- Impact: The functional UAT is interrupted at `PARTY_ANSWERS_OPEN` with USER submitted and MERCHANT pending; the same case remains resumable after the predecessor command settles.
- Identifying metadata: observed 2026-08-21; request `REQ_d217fefe768b464b9b814d75d1243fb7`; trace `TRACE_f46f38754fef90033040c1b79d9986f5`; USER command `hearing-action-HEARING_ACTION_2780188077664957ba7395e1d953f2c2`; failed stage `hearing_merchant_answer_bundle/status`.
- Regression fact: On 2026-08-24, resumed case `CASE_P9_6A8BF9E2_6` again committed the USER Hearing answer bundle, returned retryable 409 for the immediately following MERCHANT bundle, and then returned HTTP 500 on retry; the E2E stopped before Hearing M2.
- Regression root cause and evidence: Java trace `TRACE_8ae1f468367b8c419dcff5e8488cb2a1` records `ObjectOptimisticLockingFailureException` while `CaseCommandService.validateAndReserveRevision` converts an already-managed `CaseRoomEpochEntity` to a `FOR UPDATE` lock. `GlobalExceptionHandler` has no optimistic-lock mapping, so the concurrency conflict falls through to `INTERNAL_ERROR` instead of the existing retryable `CASE_STATUS_INVALID` revision-conflict contract.
- Regression identifying metadata: request `REQ_8a874fc083da4cc4887e042e006fef35`; failed stage `resume_hearing_merchant_answers`; epoch `CRE_5cbc4f56b4424bbdbe805fc114b2e32c`.

## P0-20260821-HEARING-E2-GRAPH-COMMAND-CONFLICT

- Severity: P0
- Status: CONFIRMED_REPAIR_INCOMPLETE
- Component: Target Hearing EVIDENCE_SYNTHESIZING agent execution
- Confirmed fact: The canonical UAT persisted both E1 party batches and entered `EVIDENCE_SYNTHESIZING`, but the E2 AgentRun failed before result commit with `GRAPH_COMMAND_STATE_CONFLICT` and `AgentRun V3 logical execution cannot continue`.
- Root cause and evidence: The frozen Graph command budget allowed `provider_attempts_remaining=2`, while this E2 invocation contained two evidence items and therefore required two file-assessment calls plus one final synthesis call. Both assessments completed and persisted; the third provider-intent mutation was rejected after the durable attempt count reached 2, producing `GraphCommandStateError`. The later lease cancellation/fence increment was failure cleanup, not the initiating fault. AgentRun `target-hearing-run:34454416f3243ccd8d7c05bb524817d6` is `FAILED/UNCOMMITTED`, while the public Hearing projection remains `ACTIVE` at `EVIDENCE_SYNTHESIZING`.
- Impact: No final E2 matrix is committed, so `trial_dossier.v2` freezing and Judge V1/Jury/Judge V2 cannot begin for case `CASE_P9_6A8766EC_1`.
- Identifying metadata: observed 2026-08-21; flow `ROOM_HEARING_13a15f2b896de80d3379458d9bb1`; failed run started 2026-08-21T04:58:17+08:00 and completed 2026-08-21T04:58:26+08:00.

## BUG-20260821-DISPATCHER-TEST-STREAM-PROTOCOL-V2

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target typed room dispatcher test authority fixture
- Confirmed fact: The focused dispatcher suite failed two Evidence scenarios before their intended assertions because its local `ExecuteAgentRunRequest` fixture supplied `agent-stream.v2`.
- Root cause and evidence: The formal `ExecuteAgentRunRequest` contract now requires `agent-stream.v3`, but `TargetTypedRoomCaseProcessDispatcherTest.EvidenceDispatchActivities.agentRunRequest` retained the retired literal at line 1632. Both failures terminate in the constructor with `streamProtocol must be agent-stream.v3`.
- Impact: The stale test authority blocks adjacent-regression verification for the Review epoch repair; production dispatcher execution is not implicated by this fixture-only failure.
- Verification evidence: The fixture now supplies the authoritative `agent-stream.v3` value, and all 16 dispatcher scenarios pass, including Evidence once-only dispatch, returned-terminal failure, and first Review epoch zero acceptance.
- Identifying metadata: observed 2026-08-21; test class `TargetTypedRoomCaseProcessDispatcherTest`; failing scenarios `dispatchesEvidenceOpeningExactlyOnceAndPreservesSubmissionAndCompletionCommands` and `returnedEvidenceTerminalFailureUsesDistinctMarkerAndPreservesChildThrowPath`.

## P0-20260821-EVIDENCE-FORMAL-COMMIT-COMMAND-CURSOR

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Evidence formal commit and CaseProcess recovery coordinates
- Confirmed fact: Fresh case `CASE_P9_6A87D8B7_1` committed Evidence opening command sequence 7 as `APPLIED` and advanced both the Evidence epoch and process projection to process revision 7, but the process projection retained `last_command_sequence=6`. The following Evidence submission at command sequence 8 ended with an uncommitted finalization rejection, and `ResolveTargetEvidenceTerminalNoCommit` rejected its exact durable terminal with `TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE`.
- Root cause and evidence: `JdbcTargetEvidenceFormalCommitPort.advanceProjection` advances only `process_revision`; it neither validates nor advances `last_command_sequence`, even though the same transaction marks the corresponding case command `APPLIED`. The terminal-no-commit resolver correctly requires the projection cursor to equal the immediately preceding command sequence, so it observed 6 where sequence 7 was required.
- Impact: Every successful Target Evidence formal turn can leave projection and command-ledger authority inconsistent; a later failed Evidence turn cannot converge through the existing terminal-no-commit path, leaving the case command `ORCHESTRATION_ACCEPTED` and blocking all downstream Hearing stages.
- Verification evidence: Focused Java verification passed 9/9 on 2026-08-21, including first formal commit, idempotent replay, projection-revision drift, projection-command-cursor drift, Evidence opening, and missing `ROOM_READINESS` binding.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.8b7d17085476ae4620999fe646e807cd`; opening command `evidence-opening:886d7e00eb4a3a589439dd69b4959fef`; blocked submission `evidence-submit:EVIDENCE_BATCH_2187207de0e944c187693e787f6acf3b`; failed run `target-evidence-run:6ce90d79bc4834629f7f5f031eb9df45`.

## P0-20260822-EVIDENCE-PARTY-COMPLETION-COMMAND-CURSOR

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Evidence `PARTY_EVIDENCE_COMPLETE` formal projection coordinates
- Confirmed fact: In fresh canonical case `CASE_P9_6A89144A_1`, MERCHANT Evidence opening command sequence 12 committed successfully, then MERCHANT no-upload completion command sequence 13 committed as `APPLIED` and advanced the process projection from revision 12 to 13 while leaving `last_command_sequence=12`. The following USER Evidence opening command sequence 14 produced five committed public frames, a usage event, and a final event, but formalization aborted with `Evidence projection command cursor drifted before formalization`.
- Root cause and evidence: `JdbcTargetEvidencePartyCompletionActivities.updateProjection` updates only `process_revision`, while the same transaction's `markApplied` advances the completion command to `APPLIED`; `lockProjection` also validates only the revision, and the stored-replay branch returns without repairing or rejecting the stale cursor. `JdbcTargetEvidenceFormalCommitPort.requireInitialCoordinates` then correctly requires the projection cursor to equal the immediately preceding sequence 13 and observes 12. The terminal-no-commit resolver independently rejected the same stale source boundary with `TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE`.
- Impact: A first party completing Evidence before the other party's opening leaves command-ledger and process-projection authority inconsistent. The next formal Evidence turn cannot commit, its accepted command remains `ORCHESTRATION_ACCEPTED`, and the case cannot reach evidence submission, room sealing, Hearing, Review, or Outcome. Recalling the model cannot repair this persisted coordinate mismatch.
- Verification evidence: The aborted USER run `target-evidence-run:7730c9a815b138219129dcb85a58b4db` completed at the provider boundary (`outcome=COMPLETED`, `final_frame_observed=true`) with frame order `ROOM_WELCOME`, `OPENING_ORIENTATION`, two `EVIDENCE_REQUEST` frames, and terminal `ROOM_READINESS`; `evidence_turn_projection_v2` contains no row because rejection occurred before projection persistence. The existing completion test class exercises request and helper contracts but does not execute the completion transaction or assert `last_command_sequence`.
- Focused verification: `JdbcTargetEvidencePartyCompletionActivitiesTest` passed 15/15, including fresh cursor authority, exact applied replay, stale replay rejection, and the two-column CAS update. The adjacent PostgreSQL-backed `JdbcTargetEvidenceFormalCommitPortTest` cursor scenarios passed 2/2, proving that the next Evidence formal turn accepts the advanced cursor and still rejects a stale one.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.d103a29b7b6c36f8de45b28710534b34`; case `CASE_P9_6A89144A_1`; completion `evidence-complete:EVIDENCE_COMPLETE_130f2e97ee8b432fb4e233e0e7bd0b36`; blocked opening `evidence-opening:a0af34bac37f34a1886e1cd56c7604dd`; failed run `target-evidence-run:7730c9a815b138219129dcb85a58b4db`.

## P0-20260821-INTAKE-CASE-DETAIL-STRUCTURED-OUTPUT-REJECTED

- Severity: P0
- Status: CONFIRMED_INTERMITTENT
- Component: Intake first-turn structured model output validation
- Confirmed fact: Fresh case `CASE_P9_6A87E789_1` completed import, Intake preparation, and initiator opening, then its first initiator Intake run terminated as `agent_run_error` before any later party statement was submitted.
- Root cause and evidence: The model returned 4,894 characters for node `intake_turn_case_detail`, but the returned ordered-section document matched none of the allowed Intake result variants. Validation evidence includes an invalid case-matrix value, overlong `blocking_gaps` and `next_questions`, an out-of-range `total_score`, and invalid literals for `remark_status`, `ready_for_next_step`, `admission_recommendation`, and `conversation_action`. The failed command carried `provider_attempts_remaining=2` and `repairs_remaining=1`, but the native async structured-stream path performs no Schema repair after streaming begins; the attempt therefore ended after the first invalid generation with `public_output_emitted=true` and no accepted structured result.
- Impact: A newly created canonical case cannot advance past the first USER Intake turn, so Evidence, Hearing, dossier freezing, judge review, and downstream execution are unreachable in this UAT run.
- Reproduction evidence: Two subsequent independent fresh-case probes using the same fixture and first USER Intake stage both completed and committed successfully: `CASE_P9_6A87E789_2` and `CASE_P9_6A87E789_3`. Each persisted exactly one applied Intake command and one agent message, with no second party statement submitted.
- Regression evidence: Fresh Thinking-mode case `CASE_P9_6A888D81_8` reproduced the rejection on both authorized provider calls. The first complete 6,452-character document selected the coherent ready/waiting-for-remark branch (`total_score=97`, `ready_for_next_step=true`, `admission_recommendation=ACCEPTED`, `conversation_action=INVITE_OPTIONAL_REMARK`, `remark_status=WAITING_FOR_REMARK`), but `CLAIM_AND_RESPONSE.respondent_attitude` paired `source_attribution=INITIATOR_REPORTED` with non-substantive `attitude=NOT_RESPONDED`. This violates the source/attitude model invariant; the remaining reported field failures belong to rejected alternative union branches rather than additional defects in the selected ready branch. The replacement generation repeated the same invalid pair and the run ended `AGENT_OUTPUT_SCHEMA_INVALID`.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.41594bbe11ad44834d4f406f3ce4e337`; case `CASE_P9_6A87E789_1`; failed stage `initiator_stream_1`; graph node `intake_turn_case_detail`; model response length 4,894 characters.

## BUG-20260821-REVIEW-REJECT-COPY-DRIFT

- Severity: P3
- Status: CONFIRMED_REPAIR_INCOMPLETE
- Component: Frontend Review workbench explanatory copy
- Confirmed fact: The live Review workbench exposes only `APPROVE`, `MODIFY_AND_APPROVE`, and `ESCALATE_MANUAL`, but its introductory copy still says that a reviewer may “驳回裁决草案” and that “批准、修改或驳回” requires confirmation.
- Root cause and evidence: `ReviewWorkbenchView.vue` retained explanatory strings from the retired reject-draft workflow while the rendered decision controls and frontend API decision vocabulary already use the current three-action contract. The mismatch was reproduced in the in-app browser on task `UI_TASK_A_193745`.
- Impact: Submission and persistence are unaffected, but the reviewer is told that a removed action remains available, so the UI explanation disagrees with the actual approval contract.
- Identifying metadata: observed 2026-08-21; page `/reviews/UI_TASK_A_193745`; live controls `APPROVE`, `MODIFY_AND_APPROVE`, `ESCALATE_MANUAL`.

## BUG-20260821-HEARING-DEADLINE-TIMEOUT-SCHEMA-MISMATCH

- Severity: P1
- Status: CONFIRMED_REPAIR_INCOMPLETE
- Component: Legacy Hearing deadline scheduler
- Confirmed fact: The restarted Java API repeatedly fails its scheduled Hearing deadline scan with `IllegalArgumentException: schemaVersion is not valid for actionType` before a timeout action is persisted.
- Root cause and evidence: `HearingFlowDeadlineScheduler.executeLegacyScan` calls `HearingFlowRuntimeService.expireDuePartyStages`; its `createTimeoutAction` path passes an action-type/schema-version combination rejected by `HearingFlowActionEntity.partyActionWithSchema`. The same stack recurred about every 15 seconds in Java API process 31416 while the Review endpoints remained successful.
- Impact: Due legacy Hearing party stages may not receive their scheduled timeout action, although the isolated Review interface submissions in this run were unaffected.
- Identifying metadata: observed 2026-08-21 after Java API restart on port 8081; stack paths `HearingFlowDeadlineScheduler.java:100`, `HearingFlowRuntimeService.java:641/1724/1803`, `HearingFlowActionEntity.java:154`.

## BUG-20260821-REVIEW-COPILOT-STREAM-TEXT-DUPLICATION

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Review copilot streaming presentation
- Confirmed fact: The live Review workbench renders repeated character or phrase fragments inside an explanation response on task `hearing-review-task-0fcc80845c7939a3987527286416a0c8`.
- Root cause and evidence: The repeated wording came from the frontend-generated `caseBriefingMessage`: it concatenated multiple already-prefixed risk strings into one sentence, so prefixes such as “需重点审查” and “需核实” accumulated in the rendered explanation. The message now removes those repeated lead-ins, deduplicates the normalized risks, and renders them as numbered lines.
- Impact: Reviewers can misread or lose confidence in the explanation text even though the frozen review packet and decision controls remain available.
- Verification evidence: `ReviewWorkbenchView.test.js` passes the focused scenario `formats repeated review lead-ins as a concise numbered briefing`, and the complete Review workbench component file passes 24/24 tests.
- Identifying metadata: observed 2026-08-21; page `/reviews/hearing-review-task-0fcc80845c7939a3987527286416a0c8`; visible sender `审核解释官`.

## BUG-20260821-REVIEW-JURY-SOURCE-OMITTED

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Frozen Review packet and V2 review-response presentation
- Confirmed fact: The live V2 review-response cards show the judge response and its source type, but do not show the original jury opinion that the response addresses.
- Root cause and evidence: Review packet `hearing-review-task-0fcc80845c7939a3987527286416a0c8` exposes 13 `draft.review_responses` plus the bound jury `report_id` and `report_content_hash`, while each response contains only `review_item_ref`, `review_source`, `disposition`, `response`, and `affected_fields`. The exact bound `JURY_REVIEW_REPORT` row contains the missing `proposal.findings[].assessment` and `proposal.mandatory_revisions[]` source text, but `ReviewApplicationService.packet` does not project those items into the read view.
- Impact: Reviewers see a one-sided answer and cannot compare a jury opinion with the judge's reply in the same card, even though both immutable artifacts exist and are hash-bound in the database.
- Verification evidence: After the Java API restart, the real packet endpoint returns 13 hash-bound source items (6 jury findings, 4 mandatory revisions, and 3 V1 review focuses). The in-app browser renders all 13 without fallback text, places the first `JURY_FINDING` opinion above its judge reply, and `ReviewWorkbenchView.test.js` passes 24/24 tests.
- Identifying metadata: observed 2026-08-21; case `CASE_P9_6A878C2A_1`; jury report `hearing-jury_review-3cb13ba0303e0c18845da6efbfd8fe26`; page `/reviews/hearing-review-task-0fcc80845c7939a3987527286416a0c8`.

## BUG-20260821-REVIEW-TARGET-FIXTURE-DOSSIER-CONSTRAINT-DRIFT

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Review producer integration-test fixture
- Confirmed fact: The focused `targetHearingProducerPacketPassesClosedReviewConsumerManifest` test stops before exercising the Review packet assertions because its seeded `hearing_trial_dossier.payload_json` is rejected by the current database constraint `ck_hearing_trial_dossier_payload`.
- Root cause and evidence: `ReviewApplicationServiceIntegrationTest.seedTargetReviewProducerAuthority` inserts the fixture row at line 881; PostgreSQL rejects that insert with `DataIntegrityViolationException` immediately after all 86 migrations complete. No Review packet production or source-projection assertion runs.
- Impact: The focused integration node cannot currently prove the target Review producer contract against a migrated database, although Java compilation and the frontend component tests remain independently executable.
- Identifying metadata: observed 2026-08-21; test `ReviewApplicationServiceIntegrationTest#targetHearingProducerPacketPassesClosedReviewConsumerManifest`; rejected table `hearing_trial_dossier`; constraint `ck_hearing_trial_dossier_payload`; migrated fixture rerun passed 1/1 and reached Review decision handoff assertions.

## BUG-20260821-REVIEW-JURY-FIELD-CODE-LEAK

- Severity: P3
- Status: FIXED_FOCUSED_VERIFIED
- Component: Review V2 jury-opinion text presentation
- Confirmed fact: The live V2 response cards exposed internal field names, enum codes, a policy identifier, and an untranslated legal term, including `truth_status`, `evidence_coverage_status`, `NOT_EVALUATED`, `remedy_orders`, `recommended_decision`, `POLICY_MERCHANT_REFUND_V1`, jury dimension codes, and `Direct Probative Value`.
- Root cause and evidence: `EvidenceMappedText` used the shared `mapReviewTokens` mapper, but its field dictionary covered only a few isolated fields, its enum dictionary omitted `NOT_EVALUATED` and the jury review topics, and it had no policy-reference or legal-phrase layer. The affected-field tag dictionary also omitted `recommended_decision`.
- Impact: Reviewers had to interpret storage-schema vocabulary inside otherwise human-readable jury opinions.
- Verification evidence: The Review workbench component suite passes 24/24 tests and covers field names, enum values, jury dimensions, policy references, legal terminology, and affected-field tags. The mapped presentation uses “事实认定状态为尚未认定”, “证据覆盖状态为冻结证据未覆盖”, “直接证明力”, “商家同意退款规则”, and “总体建议”.
- Identifying metadata: observed 2026-08-21; task `hearing-review-task-0fcc80845c7939a3987527286416a0c8`; page `/reviews/hearing-review-task-0fcc80845c7939a3987527286416a0c8`.

## P0-20260821-TARGET-REVIEW-DECISION-RETURNS-500

- Severity: P0
- Status: CONFIRMED_REPAIR_INCOMPLETE
- Component: Target Review final-decision submission
- Confirmed fact: The first two final-confirmation attempts returned HTTP 500 and fully rolled back; `review_task` remained `IN_REVIEW` and no `human_review_record` existed. After the current-contract Target control-plane facts were complete, the same browser page submitted successfully, navigated to `/disputes/CASE_P9_6A8866C5_UI1/outcome`, and rendered exactly one final execution event.
- Root cause and evidence: The manually created UAT case had a `case_room_epoch` for `REVIEW` at room epoch `0` and fencing token `4`, but omitted both its signed activation case reservation and immutable `target_e2e_room_epoch_binding`. The Review command material insert therefore violated `fk_target_e2e_review_material_epoch` for `(activation_id, tenant_surrogate, case_id, REVIEW, 0, 4)`. The successful submission persisted the matching command admission/material tuple, approval record, approved task state, `APPROVED_FOR_EXECUTION` case state, and action record.
- Impact: The incomplete UAT fixture blocked Review-to-Outcome verification with a rollback-safe HTTP 500; the corrected current-contract fixture no longer blocks reviewer approval or Outcome navigation.
- Identifying metadata: observed 2026-08-21; activation `p9act.v1.8b3b1c5cd08313810dd64287452fdddf`; case `CASE_P9_6A8866C5_UI1`; task `hearing-review-task-p9-6a8866c5-ui1`; page `/reviews/hearing-review-task-p9-6a8866c5-ui1`.

## BUG-20260822-OVERVIEW-IMPORT-OMITS-CANONICAL-FIXTURE

- Severity: P1
- Status: CONFIRMED
- Component: Frontend external-dispute import command
- Confirmed fact: Clicking “导入外部争议” under the fresh target activation created case `CASE_P9_6A88B179_1` from rotating template T13 (“维修后同一故障再次出现”) instead of canonical fixture `air-purifier-specification-mismatch-v1`.
- Root cause and evidence: `DisputeOverviewView.vue#simulateExternalImport` builds the import command with `count`, `scenario`, risk and party hints but no `fixture_id`; `disputeApi.simulateExternalImport` forwards that command unchanged to `POST /disputes/import/simulate`, so the backend template cursor selects a non-canonical fixture.
- Impact: A browser-driven canonical full-chain UAT begins with different parties, statements, evidence authority, and dispute facts, so its downstream result cannot validate the frozen canonical contract.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.26cda0db48946e3cf9b9799f0ff888f3`; case `CASE_P9_6A88B179_1`; page `/disputes/CASE_P9_6A88B179_1/intake`.

## P0-20260822-ACTOR-LOCAL-INTAKE-STREAM-PROTOCOL-REJECTED

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Intake final structured-stream acceptance
- Confirmed fact: In fresh canonical case `CASE_P9_6A88B179_2`, the first two USER statements completed, while the third USER statement produced a complete public reply and updated dossier text before the turn ended with `GRAPH_STREAM_PROTOCOL_REJECTED` and the UI message “数字人生成失败，请稍后重试”。
- Root cause and evidence: The failed logical run emitted 34 public frames and a complete strict-Schema document, then `GovernedChatModel._validated_result` raised `ModelPolicyViolation` at the output-budget check. Thinking-enabled Qwen reports reasoning usage inside `completion_tokens`; the Intake profile remained capped at 6,144 tokens, so an otherwise complete result was rejected before its final frame. A subsequent statement was then rejected immediately with `INTAKE_EVENT_SEQUENCE_INVALID` because the previous turn had never committed its event sequence.
- Impact: The canonical full-chain UAT cannot complete USER Intake confirmation or reach MERCHANT Intake, Evidence, Hearing, Review, and Outcome from this case.
- Verification evidence: The focused LLM request-policy test passes with the bounded 16,384-token Intake profile and Thinking enabled. Two broader legacy Intake test nodes still stop earlier on pre-refactor fixture/state shapes and do not exercise the changed budget assertion; browser UAT must restart from a fresh canonical case because the failed case retains the uncommitted logical turn.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.26cda0db48946e3cf9b9799f0ff888f3`; case `CASE_P9_6A88B179_2`; failed stage `USER Intake statement 3`; diagnostic code `GRAPH_STREAM_PROTOCOL_REJECTED`.

## BUG-20260822-INITIATOR-INTAKE-ASKS-FOR-OPPONENT-POSITION

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Initiator Intake public question generation
- Confirmed fact: Fresh canonical cases `CASE_P9_6A88B179_3`, `CASE_P9_6A88B179_4`, and `CASE_P9_6A88B179_5` all asked the USER for the merchant's attitude or handling plan. Case `_5` completed without a backend error but remained `INCOMPLETE` at score `81`; its persisted `nice_to_have_gaps`, `next_questions`, admission reasoning, and handoff instruction treated the absent merchant report as unfinished USER Intake information.
- Root cause and evidence: The exact assembled system message contained the base instruction that the initiator must not supply opponent attitude, then appended the USER profile instruction to ask for “用户所了解的商家态度” as the last role-specific rule. The fresh domain snapshot contained no prior dossier or merchant position, and the strict initiator Schema exposed no `merchant_claim`, `respondent_position`, or `respondent_attitude` field. The provider therefore returned a structurally valid attributed-question path while the contradictory prompt made the optional report affect completeness.
- Impact: The formal matrix remains structurally actor-local, but a USER who does not report the merchant's attitude can be kept below the Intake readiness threshold even though the merchant's direct position belongs to the later MERCHANT turn.
- Verification evidence: AgentRun `target-intake-run:7bb8346ac2913d2cb61e5168f3723d93` completed on its first attempt with no validation error; its persisted dossier records the merchant-attitude question and `ready_for_next_step=false`. Reconstructed production input contains only `case_identity`, `initial_case_facts`, an empty/default `previous_dispute_outline`, and empty recent dialogue, while the assembled system prompt places the contradictory USER-profile rule after the actor-local base rules.
- Regression evidence: The current role-scoped provider types expose only `initiator_position + claim_resolution` for an initiator and only `respondent_position + respondent_attitude` for a respondent. Prompt rollback commit `eb084f8b` replaced the matching role-scoped instructions with text requiring the model to populate both-party positions, requiring a respondent to copy frozen `claim_resolution`, and requiring an initiator to populate `respondent_attitude`; those fields are absent from the corresponding provider Schemas.
- Verification evidence update: The rendered Intake prompt now names only the role-scoped fields exposed by each provider Schema and assigns cross-party restoration to the server. The focused prompt, initiator/respondent Schema, component-score, materialization, durable projection, and live total projection checks passed together: 17 tests, zero failures.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.26cda0db48946e3cf9b9799f0ff888f3`; cases `CASE_P9_6A88B179_3`, `CASE_P9_6A88B179_4`, and `CASE_P9_6A88B179_5`; stage `initiator opening`; model `qwen3.7-max`; prompt-attempt count `3/3`.

## BUG-20260822-MERCHANT-INTAKE-SCORE-BREAKDOWN-MISMATCH

- Severity: P2
- Status: REOPENED_UAT_BLOCKED
- Component: Intake quality projection
- Confirmed fact: After the first direct MERCHANT statement completed successfully, the persisted dossier reported merchant Intake quality `83`, while the live frontend displayed `完善度 0%` before and after a page reload. In a later USER run, the displayed and persisted total moved from `80` to `9` after a substantive answer even though the six persisted component scores summed to `90`; after the following USER answer, the total remained `9` while the components increased to `94`.
- Root cause and evidence: The earlier accepted `party_intake_state.MERCHANT.intake_quality` stored `score=83`, but its six `score_breakdown` values summed to `88`. In case `CASE_P9_6A892399_2`, command `intake-message:59946793ec803a16b47dda05c6b966d0`, the accepted Python graph proposal itself stored `score=9` while its components were `15 + 18 + 18 + 12 + 12 + 15 = 90`; command `intake-message:018328c4067a38a3b0e44307a5787384` again stored `score=9` with components summing to `94`. The ordered provider schema treats `total_score` and `score_breakdown` as independent bounded fields, the materializer copies `total_score` directly into `intake_quality.score`, and the authoritative-model reducer deliberately preserves that total without recomputing it. Backend structured-output validation therefore accepted all internally inconsistent objects, which then propagated unchanged through the proposal, dossier, turn memory, Java persistence, and frontend.
- Impact: Intake completeness can visibly regress or display a false value despite additional substantive answers, and the displayed progress no longer represents the component assessment carried by the same accepted proposal.
- Verification evidence: Python reducer regression proves a model total of `9` is persisted and projected as the six-component sum `90`; Python proposal validation rejects a party score different from its component sum; the governed live `TURN_EVALUATION` projection rewrites the visible total to `90`; Java formal merge regression proves an incoming score `80` with components totaling `85` is normalized, replayed, and persisted as `85`. All three focused Python tests passed, and the focused Java test passed after full main/test compilation.
- Regression evidence: The current Intake V3 provider Schema still requires `TURN_EVALUATION.total_score`, and the current prompt still declares it to be the model's independent score that must not depend on backend summation. The graph stream projector and dossier reducer simultaneously continue to replace or ignore that value and use the six-component sum, so the previous persistence repair did not remove the dual authority from the provider contract.
- Verification evidence update: Both initiator and respondent provider JSON Schemas omit `total_score`; an old replay carrying the field is accepted only after discarding it, while readiness validation, visible projection, materialization, and durable state all derive the same total from the six bounded components. The focused contract group passed together: 17 tests, zero failures.
- Regression fact: In fresh USER Intake case `CASE_P9_6A8B9CE8_5`, the second substantive turn was regenerated twice with `qwen3.8-max`; both generations produced six component scores totaling `94`, `blocking_gaps=[]`, but selected `ready_for_next_step=false`, `ASK_SUBSTANTIVE`, `NEED_MORE_INFO`, and `NOT_READY`. Final Pydantic validation rejected both generations with `ready_for_next_step must follow the rubric score and blocking gaps`, and the logical run aborted with `AGENT_OUTPUT_SCHEMA_INVALID`.
- Regression root cause and evidence: The persisted Intake protocol still declares and validates the one-turn-lag state `READY_PENDING_REMARK_INVITE`, with the allowed transition `NOT_READY -> READY_PENDING_REMARK_INVITE` and frontend support for that pending state. The current ordered-output validator instead derives the current turn action state directly from the newly generated six-component sum and rejects `ASK_SUBSTANTIVE/NOT_READY` whenever that new sum reaches 85. This compares two different temporal authorities: the visible action for the current turn is selected from the previous persisted Intake state, while the newly generated six-component score becomes the next turn's state. Commit history confirms the earlier prompt explicitly required a first threshold-crossing turn to preserve the substantive question and enter `READY_PENDING_REMARK_INVITE`; the later same-turn invitation contract removed that transition from the provider/reducer path without removing it from the persisted state machine, transition validator, workflow trace, or frontend.
- Regression impact: A valid threshold-crossing substantive response can stream all ten sections, then fail only because the final Schema validator compares the new score with the previous-state-driven current action. No assistant turn or updated score commits, so the next turn never receives the threshold-crossing value that should advance it into the optional-remark invitation phase.
- Regression verification update: A provider result carrying six component scores totaling `88`, `blocking_gaps=[]`, and the previous-state-aligned `false / ASK_SUBSTANTIVE / NEED_MORE_INFO / NOT_READY` tuple now passes the provider boundary; the reducer persists `true / ACCEPTED / READY_PENDING_REMARK_INVITE` for the next turn while retaining the current substantive question. The following turn copies the persisted six component scores, absorbs one new answer, and advances only `READY_PENDING_REMARK_INVITE -> WAITING_FOR_REMARK`; direct `NOT_READY -> WAITING_FOR_REMARK` and same-turn remark invitation are rejected. The focused contract group passed 19 tests with zero failures, and the restarted target Python runtime reported `GRAPH_READY` under activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.
- Backend UAT regression fact: Fresh case `CASE_P9_6A8B9CE8_6` persisted the USER threshold-crossing turn with six-component sum `100`, `ready_for_next_step=true`, and `remark_status=READY_PENDING_REMARK_INVITE`. The next authenticated USER answer reached AgentRun `RESULT_READY` but formalization aborted with `INTAKE_HANDOFF_REMARK_PARTITION_TRANSITION_INVALID`; no replacement assistant message or turn-memory revision committed.
- Backend UAT regression root cause and evidence: Python now authorizes only the adjacent `READY_PENDING_REMARK_INVITE -> WAITING_FOR_REMARK` transition for the invitation turn, but `IntakeDossierProjectionMerger.requireHandoffRemarkPartitionTransition` still authorizes pending or waiting states only to `HAS_REMARKS`/`NO_EXTRA_REMARKS`. The same Java table still authorizes the superseded direct `NOT_READY -> WAITING_FOR_REMARK` transition. The immutable partition therefore rejects the new dossier transition after the model result is ready.
- Backend UAT regression impact: The first threshold-crossing turn commits correctly, but the required following invitation turn cannot cross the Python-to-Java persistence boundary, so neither party can complete Intake or enter Evidence.
- Backend UAT regression metadata: observed 2026-08-24; case `CASE_P9_6A8B9CE8_6`; logical run `target-intake-run:95c28fa80c9632e9a3d7201d071c1429`; current source message `MESSAGE_a06e92ab38474ad086de0535e52b8ab5`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.
- Backend UAT regression evidence update: In resumed fresh case `CASE_P9_6A8B9CE8_7`, turn 2 correctly persisted six-component sum `94`, `ready_for_next_step=true`, `ACCEPTED`, and `READY_PENDING_REMARK_INVITE` while retaining the current substantive question. On turn 3, the model received that persisted phase but emitted `ASK_SUBSTANTIVE` and kept `READY_PENDING_REMARK_INVITE`; Python rejected the result with `INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT`, exposed publicly as `GRAPH_STREAM_PROTOCOL_REJECTED`. The UAT answer addressed the merchant-alternative part of the prior utterance but did not answer the single persisted next question about third-party test conditions, which created a model recency bias toward another substantive question even though the persisted phase required `INVITE_OPTIONAL_REMARK`.
- Backend UAT regression evidence metadata: observed 2026-08-24; case `CASE_P9_6A8B9CE8_7`; logical run `target-intake-run:0cebc9fb65723f07bfb0e81f64159189`; attempt `target-intake-attempt:0cebc9fb65723f07bfb0e81f64159189:1`; error `INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.
- Backend UAT regression fact update: In fresh case `CASE_P9_6A8B9CE8_8`, turn 2 persisted score `95` and `READY_PENDING_REMARK_INVITE`. Turn 3 correctly emitted `INVITE_OPTIONAL_REMARK`, projected `WAITING_FOR_REMARK`, cleared blocking gaps and questions, and reused the prior persisted quality in the Python dossier result, but Java aborted formalization with `INTAKE_HANDOFF_REMARK_SUBSTANTIVE_DRIFT`; no turn 3 memory committed.
- Backend UAT regression root cause update: `READY_PENDING_REMARK_INVITE` still represents one retained substantive question whose next authenticated answer must be absorbed while the visible action advances to the optional-remark invitation. Java `requirePostThresholdSubstantiveFreeze` included that pending state in its frozen-state predicate and compared the entire dossier excluding only handoff fields, so legitimate case-story and party-position updates from the retained answer were rejected. The actual frozen boundary begins at the prior `WAITING_FOR_REMARK` state, after the invitation has been delivered.
- Backend UAT regression metadata update: observed 2026-08-24; case `CASE_P9_6A8B9CE8_8`; logical run `target-intake-run:39956018cab038429bc07d0d146ae8ff`; attempt `target-intake-attempt:39956018cab038429bc07d0d146ae8ff:1`; error `INTAKE_HANDOFF_REMARK_SUBSTANTIVE_DRIFT`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.
- Backend UAT regression fact update: After the Java transition and freeze repair was loaded, fresh case `CASE_P9_6A8B9CE8_12` failed on the second USER substantive turn. The provider emitted six components totaling `100`, `ready_for_next_step=true`, `ACCEPTED`, `INVITE_OPTIONAL_REMARK`, and `WAITING_FOR_REMARK` while the previous persisted actor phase remained `NOT_READY`; Python rejected the terminal result with `INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT`, and the public run ended `GRAPH_STREAM_PROTOCOL_REJECTED` with `retryable=false` and no `generation_reset`.
- Backend UAT regression root cause update: The ordered provider model validates the internal score/gap/action branch but has no request-bound validator for the previous persisted actor phase. The previous-phase action check runs later in `DossierSkill.render_case_detail`, after the governed structured stream has already passed its final Pydantic boundary. Consequently a phase-invalid but internally coherent document bypasses the existing same-input full-regeneration mechanism and is classified as a non-retryable graph protocol failure.
- Backend UAT regression metadata update: observed 2026-08-24; case `CASE_P9_6A8B9CE8_12`; logical run `target-intake-run:f4033006a3ea3e28a488adaec8dcf33b`; attempt `target-intake-attempt:f4033006a3ea3e28a488adaec8dcf33b:1`; terminal event sequence `37`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.
- Backend UAT regression fact update: After the request-bound previous-phase provider validator and dynamic initiator routing were loaded, fresh case `CASE_P9_6A8B9CE8_15` completed USER Intake and progressed through the fourth MERCHANT response. The fourth MERCHANT AgentRun reached a model result, but Java formalization rejected the dossier with `INTAKE_HANDOFF_REMARK_SUBSTANTIVE_DRIFT`; the UAT harness surfaced the terminal failure as `sequence_continuity`.
- Backend UAT regression evidence metadata update: observed 2026-08-24; case `CASE_P9_6A8B9CE8_15`; logical run `target-intake-run:a0b3d85179e4348fb5b5f7fdfbac0b86`; error `INTAKE_HANDOFF_REMARK_SUBSTANTIVE_DRIFT`; activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`; Java merger class timestamp `2026-08-24 13:46:36`, preceding the `14:29` failure.
- Backend UAT split-commit evidence update: The rejected fourth MERCHANT turn nevertheless committed Graph cognitive revision `4` and checkpoint `1f19f853-20e8-6935-8026-696580052ee7`; its verified `baseline_previous_case_detail` contains terminal `NO_EXTRA_REMARKS`, while `case_intake_dossier` remained at source turn `3` with MERCHANT `WAITING_FOR_REMARK`. A later new-message retry therefore observed a terminal Graph state against a non-terminal Java dossier and failed provider Schema validation; this is a cross-boundary commit divergence, not a stale initialization snapshot or a new model-content defect.
- Backend UAT split-commit metadata update: observed 2026-08-24; graph thread `grt.v1.01a03273fbd1763089b2253a6e83ae9b`; committed command `intake-message:a0b3d85179e4348fb5b5f7fdfbac0b86`; follow-up failed logical run `target-intake-run:3590f4f9307238c685c3d3f5811f0153`.
- Backend UAT verification update: Fresh case `CASE_P9_6A8B9CE8_16` completed the full USER Intake and the MERCHANT adjacent lifecycle `NOT_READY -> READY_PENDING_REMARK_INVITE -> WAITING_FOR_REMARK -> NO_EXTRA_REMARKS`. Every Intake AgentRun finalized as `COMPLETED / COMMITTED`; the terminal MERCHANT dossier persisted score `95`, source turn `4`, and `NO_EXTRA_REMARKS`, then the process opened Evidence without another terminal message or a finalization rejection.
- Backend diagnostic correction: Exact read-only checkpoint replay of generic-identity case `CASE_P9_6A8BF9E2_1` proved the USER initiator authority already existed in both `memory_summary.authorized_initial_case_facts.initiator_role=USER` and the snapshot-bound `node_results["matrix-authority:v1"].initiator_role=USER`. The first USER `ROOM_MESSAGE` nevertheless built an `IntakeTurnRequest` with `initial_case_facts=None` and `previous_case_detail=None`; the dossier binder consulted only those two request branches and the previous claim, so it returned no proven initiator role and rejected an otherwise valid model claim whose role matched the frozen authority.
- Backend diagnostic root cause and impact: The request assembly boundary failed to propagate verified matrix authority as a server-owned, non-provider field. The failure tuple is `ROOM_MESSAGE + empty previous dossier + valid frozen matrix authority`; it aborts the first substantive room turn after provider parsing and materialization even though no role drift exists. Missing, foreign, malformed, or conflicting authority must continue to fail closed.
- Backend diagnostic metadata update: observed 2026-08-24; activation `p9act.v1.5d45e2d3e9da784527ce3a594780a6f1`; case `CASE_P9_6A8BF9E2_1`; logical run `target-intake-run:9d4bddb652883aa39e20dba1d15559c5`; innermost error `INTAKE_PARTY_STATE_ROLE_AUTHORITY_INVALID`; provider output sum `75`, `NOT_READY`, `ASK_SUBSTANTIVE`.
- Backend UAT phase-lock recurrence: In fresh canonical case `CASE_P9_6A8BF9E2_6`, USER turn 2 committed six-component sum `100` and next-turn phase `READY_PENDING_REMARK_INVITE`. USER turn 3 run `target-intake-run:b455157be3ce35058de86dd23f832c41` generated the same internally coherent but phase-invalid document twice: sum `100`, no blocking gaps, retained `READY_PENDING_REMARK_INVITE`, and `ASK_SUBSTANTIVE`. The governed stream emitted one `generation_reset` and then aborted with `AGENT_OUTPUT_SCHEMA_INVALID`; no dossier update committed.
- Backend UAT phase-lock root cause and impact: The request-specific Pydantic subclass adds only an after-validator and explicitly preserves the base provider JSON Schema byte-for-byte. The provider therefore still sees every action branch and can repeatedly choose `ASK_SUBSTANTIVE` even though the previous persisted phase makes `INVITE_OPTIONAL_REMARK` the only legal current action; rejection occurs only after the full response is generated. The current input also repeated general facts without answering the retained institution-name question, increasing recency bias toward the structurally exposed but unauthorized branch.
- Regression identifying metadata: observed 2026-08-24; case `CASE_P9_6A8B9CE8_5`; command `intake-message:e6bafff4ae653a7d84512418ff7a254b`; logical run `target-intake-run:e6bafff4ae653a7d84512418ff7a254b`; attempt `target-intake-attempt:e6bafff4ae653a7d84512418ff7a254b:1`; model `qwen3.8-max`; reset sequence `37`.
- Identifying metadata: observed 2026-08-22; cases `CASE_P9_6A89144A_1` and `CASE_P9_6A892399_2`; USER graph thread `grt.v1.01a027d687fc711aad202de9fee95600`; USER result `result.520986ba85418635b9e1d6c7e5fc3417`; actor `user-local/USER`; model `qwen3.7-max`.

## BUG-20260822-INTAKE-LIVE-RUN-SHOWS-STALE-READY-STATE

- Severity: P2
- Status: CONFIRMED
- Component: Frontend Intake live-generation readiness presentation
- Confirmed fact: During an active MERCHANT Intake generation, the right dossier continued to show the previously persisted ready score and next-step copy, while the message input and confirmation action were unavailable. When the generation completed, the same controls became available again without a page navigation or a party-completion command.
- Root cause and evidence: `caseDetailQuality` falls back to the last persisted party quality whenever the active stream has not yet produced a valid `intake_quality` section, while `ConversationStream` and `intakeDossierSubmissionDisabled` independently lock actions whenever `intakeStreamingRuns.length > 0`. The readiness copy and the interaction lock therefore derive from different lifecycle snapshots during one active run.
- Impact: A party can see a ready/frozen-looking dossier at the same time that every submission control is locked, making a normal in-progress generation appear to have automatically submitted or inconsistently frozen the Intake state. No backend party completion is created by this presentation conflict.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89144A_1`; actor `merchant-local/MERCHANT`; active run `target-intake-run:7f44377dbb6c37ad953561a368cba586`; backend remained `respondent_status=OPEN` and `current_actor_completed=false` after the run.

## P0-20260822-POST-THRESHOLD-REMARK-RESPONDENT-ATTITUDE-AUTHORITY-REJECTED

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED
- Component: Target Intake post-threshold remark handoff
- Confirmed fact: In fresh canonical case `CASE_P9_6A892399_1`, the USER Intake dossier reached `92%` and exposed the confirmation action after the first participant answer. The next authenticated USER message entered the remark phase, streamed no committed agent response, and terminated with UI diagnostic `GRAPH_STREAM_PROTOCOL_REJECTED`.
- Root cause and evidence: Python recorded `IntakeGraphContractError` with code `INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID` at `app.graphs.intake.lcel:_require_exact_handoff_inherited_respondent_attitude:2819`. The post-threshold remark draft's inherited `respondent_attitude` did not satisfy the exact frozen-source authority/equality invariant against `baseline_previous_case_detail`, so final graph normalization rejected the otherwise valid remark-phase request.
- Impact: The initiator cannot persist a post-threshold remark or complete Intake, blocking MERCHANT Intake and every downstream Evidence, Hearing, Review, and Outcome stage in the fresh full-chain UAT.
- Verification evidence: The focused post-threshold remark regression passes and proves that a model-supplied changed value is replaced by the frozen prior value, an absent prior remains absent, and neither the graph state nor parsed provider result is mutated in place. Ruff passes for the changed implementation and test files.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.01cc6e51dbd18e8464b53862b3440fd9`; case `CASE_P9_6A892399_1`; stage `USER Intake post-threshold remark`; outer diagnostic `GRAPH_STREAM_PROTOCOL_REJECTED`; inner code `INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID`.

## BUG-20260822-LOCAL-TEMPORAL-COMPATIBLE-BUILD-SET-SATURATED

- Severity: P1
- Status: CONFIRMED
- Component: Local source Target-E2E launcher / Temporal worker-version routing
- Confirmed fact: A source activation compiled successfully and provisioned activation `p9act.v1.684e4c808941e92bec288a8449da4e9a`, but startup stopped before any application service launched with `LOCAL_DOCKER_TEMPORAL_BUILD_UPDATE_UNPROVEN:case-control`.
- Root cause and evidence: `case-control` still had `local-final-control.local-af17d10c-cb7edbd6d5ebbe840afb7f294ac4987e5185470f2a445656d40397186594a827-control` as its default compatible set. That set already contained a long accumulated history of compatible build IDs. The launcher attempted to append expected build `local-final-control.local-af17d10c-36f0edf92e227df337ad73d3c89850329c91e512d90217cb30b7f7f126fc1eb5-control` with `add-new-compatible`; the reconciled routing read did not contain the expected build and therefore could not prove it as default.
- Impact: The freshly compiled Java overlay, Max/no-thinking model configuration, and browser UAT cannot be launched even though compilation and activation provisioning succeeded; no new UAT case can be created while all application listeners remain stopped.
- Identifying metadata: observed 2026-08-22; task queue `case-control`; source HEAD `af17d10c`; candidate worktree binding `36f0edf92e227df337ad73d3c89850329c91e512d90217cb30b7f7f126fc1eb5`; launcher `launch-source.ps1`.

## P0-20260822-MERCHANT-INTAKE-RESPONDENT-ATTITUDE-SOURCE-UNRESOLVED

- Severity: P0
- Status: CONFIRMED
- Component: Target Intake MERCHANT structured-result finalization
- Confirmed fact: Fresh canonical case `CASE_P9_6A89420B_1` completed USER Intake and accepted the first MERCHANT statement. The second MERCHANT statement produced a complete public reply and transient dossier projection, then ended with `INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED`; the frontend restored the last persisted 90% dossier and disabled “确认陈述并进入证据室”.
- Root cause and evidence: The Python Intake finalization path still applied legacy cross-field/source-binding consistency checks after the typed `matrix_patch.respondent_claim` had been parsed. Multiple legacy branches shared the same public diagnostic family, so a valid typed MERCHANT claim could be rejected during final materialization even though the public response and transient dossier had already been generated. The finalization path now binds the typed claim to the authenticated current message without prose reclassification or duplicate display-field/source-quote comparison; 16 focused regression cases passed and the Python service restarted healthy.
- Impact: The observed logical turn remains terminal `ABORTED` and was not committed, so this existing case still cannot enter Evidence. The production fix is focused-test verified, but this exact persisted turn has no public same-message replay entry and therefore cannot serve as the post-fix UAT proof without creating another party message.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.7f387cd9fcf9649e7ee78d6319241609`; case `CASE_P9_6A89420B_1`; actor `merchant-local/MERCHANT`; stage `MERCHANT Intake statement 2`; diagnostic `INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED`; model `qwen3.7-max`; thinking disabled.
## BUG-20260822-INTAKE-VERIFICATION-FOCUS-UNMAPPED

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED_PENDING_UAT
- Component: Intake dossier verification-focus presentation
- Confirmed fact: In fresh browser UAT case `CASE_P9_6A89420B_3`, the “下一步核验重点” list rendered the raw identifiers `user_claimed_specific_performance_metrics` and `user_merchant_communication_details` after USER statement 1. At USER Intake 100%, the expanded list also rendered literal role tokens inside otherwise Chinese sentences. The defect recurred in fresh generic-identity case `CASE_P9_6A8AC2C9_5`, where both the compact dossier list and its expanded dialog exposed `user_claim_merchant_proposed_retest_specific_standards_or_procedures`, `user_additional_claims_or_special_attention_requests_beyond_return_refund`, and `user_actual_usage_scenario_compliance_with_product_instructions`.
- Root cause and evidence: The presentation mapping covers only a bounded set of previously observed keys and still falls back to raw model/backend identifiers for new verification-focus keys; role-token cleanup does not convert these full snake_case identifiers. Browser screenshots `codex-clipboard-562b1609-61e9-450c-b996-5ae6a811207e.png`, `codex-clipboard-37d4c86b-b6fc-4254-8bc6-4800eaa94314.png`, `codex-clipboard-01606306-670c-4d1a-8750-2915efa67608.png`, and `codex-clipboard-55afabd8-c6fe-482d-8f6d-871056bc7780.png` record the manifestations.
- Impact: Reviewers and parties see internal field identifiers instead of understandable Chinese verification items; the underlying Intake command remains usable.
- Identifying metadata: observed 2026-08-22 and recurred 2026-08-23; cases `CASE_P9_6A89420B_3` and `CASE_P9_6A8AC2C9_5`; actor `user-local/USER`; stage `USER Intake verification-focus expansion`.

## BUG-20260822-FRONTEND-DOMAIN-CODES-UNMAPPED

- Severity: P2
- Status: FIXED_VERIFIED
- Component: Frontend domain-code presentation across Intake, Evidence, Hearing, Review, and Outcome
- Confirmed fact: The persisted Hearing history for `CASE_P9_6A89604E_3` previously rendered internal enum and field tokens directly in user-facing copy. After the first mapping pass, the live Draft DOM still renders `Merchant-approved refund policy`, `Unshipped order cancellation policy`, and the legal-output field term `finding` inside Chinese adjudication narratives, while the Draft template itself exposes English section labels.
- Root cause and evidence: Human-language conversion is split among page-local dictionaries and `displayText.js`; the shared immutable/model-authored narrative mapper does not include the two persisted rule display names or the standalone `finding` term, and the Draft section labels are fixed English template text. CDP audits of `/disputes/CASE_P9_6A89604E_3/hearing?view=history` and `/disputes/CASE_P9_6A89604E_3/draft` confirm the original and residual presentation paths.
- Impact: Parties and reviewers still encounter untranslated implementation or model-contract vocabulary inside otherwise Chinese Draft content.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89604E_3`; routes `/disputes/CASE_P9_6A89604E_3/hearing?view=history` and `/disputes/CASE_P9_6A89604E_3/draft`; actor `user-local/USER`; projection `hearing-flow-projection.v5`; Draft residuals confirmed by CDP on 2026-08-22.

## BUG-20260822-HEARING-ANSWER-COMPOSER-CLIPPED

- Severity: P1
- Status: CONFIRMED
- Component: Hearing party-answer composer layout
- Confirmed fact: In fresh browser UAT case `CASE_P9_6A89420B_3`, the four USER answer textareas are mounted, enabled, writable, and styled with `pointer-events: auto`, but the visible answer dock does not expose them for pointer or keyboard input.
- Root cause and evidence: The `.stage-input-bar--fixed-dock` element has a 154px border-box, `overflow: hidden`, and 762px scroll content. Its `.stage-input-bar__composer` child has a 100px border-box with 720px scroll content, while `.hearing-statement-workspace` has a 94px border-box with 720px scroll content. The first textarea begins at viewport y=945 and its center hit-test resolves to the enclosing `MAIN`, proving the 4-item answer form is clipped outside the interactive paint area rather than disabled.
- Impact: Neither party can enter the required four Hearing answers, so M2 freezing and all downstream Hearing, Review, and Outcome stages are blocked.
- Verification evidence: On the same persisted UAT route, the active answer textarea is visible, enabled, and resolves to `TEXTAREA` in center-point hit testing; its rendered height is 90px inside a 180px answer dock. The question pane independently scrolls, the answer-stage party-status header is absent, and the two focused Hearing component tests pass.
- Identifying metadata: observed 2026-08-22; route `/disputes/CASE_P9_6A89420B_3/hearing`; stage `Hearing · 双方回答`; actor `user-local/USER`; screenshot `codex-clipboard-51a43fcd-0f83-4d13-85ea-e46b2ebfef40.png`.

## BUG-20260822-HEARING-PARTY-ANSWERS-NOT-UNSEALED

- Severity: P1
- Status: CONFIRMED
- Component: Target Hearing party-answer public transcript projection
- Confirmed fact: Case `CASE_P9_6A89420B_3` persisted terminal `HEARING_ANSWER_BUNDLE_SUBMITTED` events for both USER and MERCHANT, each containing four answer units. After the second submission advanced the flow to `EVIDENCE_REQUESTS_GENERATING`, the USER room-message read returned only the USER `PARTY_TEXT`, the MERCHANT read returned only the MERCHANT `PARTY_TEXT`, and the PLATFORM_REVIEWER read returned both messages.
- Root cause and evidence: Structured answer submission creates each transcript acknowledgement through `appendPrivatePartyMessage`, which persists an actor-specific audience containing only the sender ID. The party-stage completion path advances the workflow and commits the public “双方回答已封存” stage notice, but no persisted public counterpart message or visibility projection is produced for the two completed answer messages. `RoomMessageService.list` then continues to enforce the unchanged actor-specific audience on every party read.
- Impact: Once both parties have submitted, the public courtroom transcript still omits the counterparty answer card for each party, violating the unified-disclosure baseline while downstream synthesis continues with both persisted bundles.
- Identifying metadata: observed 2026-08-22; first case `CASE_P9_6A89420B_3`, room `ROOM_HEARING_bbe8431bda8e1ed09a555cdb59d4`, USER message `MESSAGE_5b2ffa9632f44bbc885a52c61f4a2860`, MERCHANT message `MESSAGE_57813c094b2c486fbbf928e37bed2fa4`, answer submission event sequences `46` and `48`; reproduced in completed case `CASE_P9_6A89604E_3`, where USER read includes sequence `15` and omits MERCHANT sequence `16`, MERCHANT read includes sequence `16` and omits USER sequence `15`, while PLATFORM_REVIEWER reads both.

## P0-20260822-HEARING-RETRY-DROPS-PARTY-DEADLINE-AUTHORITY

- Severity: P0
- Status: CONFIRMED
- Component: Target Hearing AgentRun retry materialization and evidence-request finalization
- Confirmed fact: After both parties submitted their Hearing answer bundles in case `CASE_P9_6A89420B_3`, the first `HEARING_EVIDENCE_REQUESTS` attempt ended with a retryable graph lease loss. The automatically allocated second attempt completed its graph result and produced a canonical `hearing_evidence_requests.v1` proposal containing nine request groups, but Java formal finalization then failed non-retryably and the browser received HTTP 500.
- Root cause and evidence: The initial command material row contains `party_stage_authority` with the case, epoch, fence, 1,200-second party window, and Hearing deadline. `TargetE2eAgentRunV2RetryPreparation.persistHearing` reconstructs the later-attempt `TargetHearingCommandMaterial` through the compatibility constructor that supplies no party-stage authority; the persisted second-attempt material therefore has `party_stage_authority = null`. When the completed proposal is mapped to the successor `PARTY_EVIDENCE_OPEN` stage, `FormalAuthorityBinding.withPartyStageDeadline` rejects that missing value with `target Hearing party deadline authority is absent`, and Temporal records `AgentRunFinalizationRejected`.
- Impact: A transient retryable failure in any Hearing agent stage whose successor opens a shared party window can become an unrecoverable formalization failure. In the observed case, the valid evidence-request proposal was not committed, the flow remained at `EVIDENCE_REQUESTS_GENERATING`, and the full-chain UAT could not reach party evidence submission or later adjudication stages.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.7f387cd9fcf9649e7ee78d6319241609`; case `CASE_P9_6A89420B_3`; logical run `target-hearing-run:59f41d6c8059318c8ed7d67f21da85b8`; initial command `hearing-stage:7:59f41d6c8059318c8ed7d67f21da85b8`; retry command `agent-command:e002043c118b41f7d1b5fbbb69c4b9c7`; outer code `AGENT_RUN_FINALIZATION_REJECTED`; inner error `target Hearing party deadline authority is absent`.

## P0-20260822-HEARING-M2-PARTY-UPDATE-SCHEMA-CONFLICT

- Severity: P0
- Status: REOPENED
- Component: Hearing M2 synthesis structured-output contract
- Confirmed fact: Two independent fresh canonical cases reached Hearing M2 after both parties submitted all required answers. Each model produced a complete synthesis document, but Python rejected multiple `matrix_effects.existing_fact_effects[*].party_updates.<ROLE>` objects and Java returned HTTP 500 without committing the M2 result.
- Root cause and evidence: `HearingFactPartyUpdateV4.stance` exposes the shared `FactStance` enum to the provider, including `NOT_ADDRESSED`, while an after-model validator rejects that same value for every non-null current update. The validation locations are the complete role update objects, which is the location emitted by this after-model validator. The provider-visible contract therefore permits a value that the final Python contract forbids. The first case rejected a 7,839-character result; the second rejected a 10,167-character result at four role-update objects. Both subsequent full-regeneration calls ended in `ModelTransportOutputError`.
- Impact: Hearing M2 remains `UNCOMMITTED`, the run terminates `ABORTED/AGENT_OUTPUT_SCHEMA_INVALID`, and the full chain cannot advance to evidence review, dossier freezing, adjudication, human review, or Outcome.
- Verification evidence: The focused regression first failed at the exact after-model rejection, then passed after removing semantic reclassification. It proves that a model-owned `NOT_ADDRESSED` fact update materializes as a canonical no-direct-source position while retaining the model's summary. A second regression proves that model-owned answer references are accepted without cross-party rejection across issue, fact, and claim output paths. Ruff reports no violations in the changed Python implementation and tests.
- UAT evidence: A third fresh canonical case completed both Intake parties, both Evidence parties, and all eight Hearing answers after the focused repair. Hearing M2 run `target-hearing-run:4adf4cef52fe3e58a3c383ff76b40c42` completed and committed `hearing_intake_synthesis.v5`; the same case then completed E1, E2, dossier freezing, and entered Judge V1. The browser-visible HTTP 500 was emitted by a later replay request for the already committed M2 stream and did not represent an M2 binding failure.
- Regression evidence: Fresh case `CASE_P9_6A8AC2C9_4` again reached Hearing M2 after both parties submitted all four answers. Run `target-hearing-run:afdff0472bde32a786756ecd145b8253` emitted public synthesis text, then Python rejected all eight `issue_rebindings[*].party_bindings.<ROLE>` objects with `AGENT_OUTPUT_SCHEMA_INVALID`. `HearingIssuePartyBindingV4.action_position_shape` imposes a post-Schema semantic pairing between model-owned `binding_action` and `current_position`; the provider JSON Schema cannot express that validator. Although the logical run carries `attempt_limit=3`, this validation failure was classified non-retryable and aborted after attempt 1.
- Verification evidence update: The focused regression proves that a model-owned `binding_action=NO_POSITION` with a non-null model-owned `current_position` now parses and materializes without backend reclassification; the existing canonical rebinding projection regression remains green (`2 passed`).
- Latest regression fact: Fresh browser-UAT case `CASE_P9_6A8B9CE8_1` emitted and committed all four public M2 synthesis frames, then run `target-hearing-run:c034ef789139375bb8e4ea1a5f7341b0` terminated `ABORTED / UNCOMMITTED` with outer diagnostic `AGENT_OUTPUT_SCHEMA_INVALID` and internal diagnostic `HEARING_SYNTHESIS_MATRIX_ISSUE_AUTHORITY`. No M2 result or downstream Hearing action committed.
- Latest regression root cause and evidence: Python accepted the provider JSON as `HearingIntakeSynthesisLlmOutputV5` and only failed while materializing `matrix_effects[*].source_issue_refs`. The context names every old issue plus all five reserved `NEW_ISSUE_SLOT_*` values as `allowed_issue_refs`, while the final materializer accepts old issues plus only new slots activated by an actual `new_issue_proposals` entry. The same prompt says both “copy only from `allowed_issue_refs`” and “reference a new slot only after activation.” The single provider call used `qwen3.7-max-2026-06-08`, produced 5,985 completion tokens, and no retry generation was started because this after-model materialization error occurs outside the structured-stream final-validation retry boundary.
- Latest impact: The browser receives a terminal generation-failure modal after displaying apparently complete M2 public output; the current case cannot freeze M2 or contribute to the consecutive full-chain UAT count.
- Latest verification evidence: Seven focused regressions pass. They prove that reserved issue slots are absent from the reference authority catalog, an unactivated reserved slot is rejected with `HEARING_SYNTHESIS_MATRIX_ISSUE_AUTHORITY`, M2 materialization participates in the model-output validation boundary, async structured streaming retains that validator across a generation reset, the V5 frame bridge clears provisional frames before generation 2, the ordinary V5 no-reset path is unchanged, and the underlying retry client replays one byte-identical request after final validation failure.
- Identifying metadata: observed 2026-08-22; cases `CASE_P9_6A89604E_1`, `CASE_P9_6A89604E_2`, and `CASE_P9_6A89604E_3`; repaired UAT run `target-hearing-run:4adf4cef52fe3e58a3c383ff76b40c42`; node `hearing_intake_synthesis`; result schema `hearing_intake_synthesis.v5`.

## P1-20260822-COMPLETED-AGENT-RUN-SSE-REPLAY-FALSE-FAILURE

- Severity: P1
- Status: FIXED_VERIFIED
- Component: Completed AgentRun SSE replay and frontend failure presentation
- Confirmed fact: In case `CASE_P9_6A89604E_3`, the browser repeatedly displayed `流式连接失败（HTTP 500）` while the underlying Hearing M2 run was already `COMPLETED/COMMITTED`. Dismissing the modal exposed continued downstream progress through E1, E2, dossier freezing, and Judge V1.
- Root cause and evidence: The Hearing start-event publisher sends every `AGENT_RUN_STARTED` discovery descriptor to USER, MERCHANT, PLATFORM_REVIEWER, and ADMIN, while automatic internal Hearing runs bind their AgentRun stream audience exclusively to SYSTEM. `HearingCourtView` consumes every supported descriptor without checking stream authority, so a MERCHANT browser repeatedly requests the SYSTEM-only M2 stream. The ordinary run endpoint and the stream endpoint with `Accept: */*` both expose the underlying `403 actor cannot read this agent run`; with the frontend's actual `Accept: text/event-stream`, Spring cannot render that JSON rejection through the SSE response contract and returns an empty HTTP 500. The frontend retries connection failures eight times and then presents the generic stream-failure modal.
- Impact: A successfully committed business stage is presented as a terminal generation failure; dismissing the modal is required to see progress, and repeated replay attempts can display the same false failure again even though downstream orchestration is healthy.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89604E_3`; run `target-hearing-run:4adf4cef52fe3e58a3c383ff76b40c42`; replay cursor `last_event_id=-1`; browser error `HTTP 500`; verified 2026-08-22 after explicit `stream_access=INTERNAL_SYSTEM_ONLY` publication and frontend fail-closed filtering, with the reloaded history page showing no stream-failure modal.

## P1-20260822-JURY-REPORT-HISTORY-DOUBLE-TRUNCATION

- Severity: P1
- Status: FIXED_VERIFIED
- Component: Hearing jury artifact publication and courtroom history presentation
- Confirmed fact: Case `CASE_P9_6A89604E_3` persisted a completed jury artifact with six findings and four mandatory revisions, but the courtroom history card shows only the opening fragment ending near `requ...` and provides no complete-report expansion.
- Root cause and evidence: The `JURY_REVIEW_REPORT` artifact contains a 3,786-character structured `proposal`; publication stores only its 315-character `public_message` in `room_message`. `HearingCourtView.formatJuryReviewReport` receives no structured payload on this message path and passes the fallback text through `compactReportSection`, which unconditionally truncates it to 84 characters. The resulting text is below the 1,500-character long-report threshold, so the page never renders `查看完整长报告`. On the same missing-payload path, the risk and confidence renderers substitute hard-coded defaults `中风险` and `75/100`, which match the displayed badges rather than persisted jury fields.
- Impact: The jury model and formal handoff are complete, and Judge V2 consumes the complete bound report, but court users cannot inspect the six findings or four mandatory revisions and cannot even read the complete public summary from history; the visible risk and confidence badges are also not bound to the persisted report.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89604E_3`; jury run `target-hearing-run:4b79e314838c38da97f33e80bde6c922`; artifact schema `jury_review_report.v1`; room message sequence `34`; verified 2026-08-22 through `hearing-flow-projection.v5` / `jury-review-public-projection.v1` with six findings, four mandatory revisions, highest severity `BLOCKER`, and complete expandable browser content.

## BUG-20260822-OUTCOME-DRAFT-STRUCTURED-REASONING-DROPPED

- Severity: P2
- Status: CONFIRMED
- Component: Adjudication draft persistence and Outcome API projection
- Confirmed fact: The frozen V2 adjudication artifact for case `CASE_P9_6A89604E_3` contains a 766-character `draft.decision_reasoning` value and one structured `draft.remedy_orders` item, while the corresponding `adjudication_draft` projection and `AdjudicationDraftView` expose only the 7,292-character merged `draft_text`.
- Root cause and evidence: `finalizeJudgeV2` validates and persists the complete `adjudication_draft.v3` artifact, but `persistAdjudicationDraftProjection` writes selected arrays and `public_text` into `AdjudicationDraftEntity`; `AdjudicationDraftView` has no `decisionReasoning` or `remedyOrders` components. A read-only PostgreSQL comparison of `hearing_flow_artifact` and `adjudication_draft` for the same draft ID confirms the structured fields remain in the frozen artifact and are absent from the Outcome projection contract.
- Impact: The Draft page cannot bind裁决理由与处理事项 independently and must either render the entire merged public text or perform unsafe presentation-layer text splitting.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89604E_3`; draft `hearing-judge_v2-9bb044228dbee6d74b39d4fb35a3d57a`; artifact schema `adjudication_draft.v3`; Outcome route `/api/disputes/CASE_P9_6A89604E_3/outcome`.

## BUG-20260822-OUTCOME-JURY-RESPONSE-BINDING-DROPPED

- Severity: P2
- Status: CONFIRMED
- Component: Outcome adjudication-draft jury review projection
- Confirmed fact: Case `CASE_P9_6A89604E_3` has one immutable `JURY_REVIEW_REPORT` containing six findings and four mandatory revisions, and its immutable V2 `ADJUDICATION_DRAFT` contains ten matching jury-side `review_responses`, but the Outcome API exposes neither the source opinions nor their judge responses.
- Root cause and evidence: The V2 artifact binds the jury report through `report_id`, `report_content_hash`, `trial_dossier_id`, and `trial_dossier_hash`, and every response binds a source item through `review_item_ref`. `CaseOutcomeService` currently reads only `draft.decision_reasoning` and `draft.remedy_orders` from the V2 artifact and does not load or project the bound `JURY_REVIEW_REPORT`.
- Impact: The adjudication draft page cannot render a truthful paired “陪审意见 / 法官回复” section from structured authority and would otherwise have to omit the jury review or parse the merged public text.
- Identifying metadata: observed 2026-08-22; case `CASE_P9_6A89604E_3`; jury report `hearing-jury_review-2b4e8dc0fc44949c25fe8311c0b5377a`; V2 draft `hearing-judge_v2-9bb044228dbee6d74b39d4fb35a3d57a`; Outcome route `/api/disputes/CASE_P9_6A89604E_3/outcome`.

## P0-20260822-BOUNDED-DECISION-ACTION-HANDOFF-CONTRACT-DRIFT

- Severity: P0
- Status: FIXED_UAT_VERIFIED
- Component: Target Review bounded decision-action submission and Outcome handoff
- Confirmed fact: Submitting `APPROVE` for fresh case `CASE_P9_6A89E0EC_6` returned HTTP 500 while the Review task remained `IN_REVIEW` and no approval/action material was persisted. A JDWP breakpoint captured the exact exception `target APPROVE must exactly replay the frozen remedy`.
- Root cause and evidence: The active packet carries the authoritative bounded-contract discriminators `prompt_version=hearing-flow.v2` and `profile_version=hearing-judge-v2`. Current source and `target/classes` contain the bounded `decision_action` handoff branch, but the Java runtime classpath points at stale `target/target-e2e-classes`: its `ReviewTargetDecisionHandoffWriter.class` is 20,692 bytes with SHA-256 `A553F7315B88D94563B3FA0ED9F753189DD527F44984C975974ACBCE86298BC7`, while the current compiled class is 23,710 bytes with SHA-256 `B71FEB7B7A1047DB46F33387F4C20E5743354AAF8EEDDE76103C65556AE7986B`. `javap` confirms the active class invokes only legacy `requireApprovedOperation` and contains no bounded handoff call.
- Impact: The running API rejects both approval and bounded action changes at the Review-to-Outcome boundary even though the persisted packet and current source use the new contract.
- Verification evidence: After loading the current target-E2E overlay and restarting all Java roles, the same `IN_REVIEW` task accepted `APPROVE`, navigated to `/disputes/CASE_P9_6A89E0EC_6/outcome`, and rendered published execution event `ACT_2c88950d27e0ab8fa6029770e3ee880a` without replaying earlier stages.
- Identifying metadata: re-observed 2026-08-23; case `CASE_P9_6A89E0EC_6`; task `hearing-review-task-6b96f8d65d083b11a83b729b9b3f6163`; packet `hearing-review-packet-5f6a24a8471633129c09a176ae87f8e1`; active Java PID `108408`; JDWP `127.0.0.1:5005`.

## P0-20260822-TARGET-REVIEW-ACTIVATION-EXPIRES-BEFORE-HUMAN-DEADLINE

- Severity: P0
- Status: FIXED_UAT_VERIFIED
- Component: Target-E2E activation lifetime and long-running Review authorization
- Confirmed fact: The Review task for case `CASE_P9_6A89604E_3` still presents approximately 162 hours of human-review time, but both pre-restart and post-restart submissions return HTTP 500 before any review decision is persisted. The post-restart request submitted `ESCALATE_MANUAL` and the task remained `IN_REVIEW`.
- Root cause and evidence: Activation `p9act.v1.15cfe8b30223eaade5de9283c3dc3171` is stored as `ACTIVE` but its signed authorization cutoff is `2026-08-22 18:39:32+08`, while the confirming request ran at `2026-08-22 23:28:27+08`. `JdbcTargetE2eApiAuthority.ACTIVE_ACTIVATION` requires both `lifecycle_status = 'ACTIVE'` and `expires_at > clock_timestamp()`, so `ReviewApplicationService.targetRoute` receives no grant and throws `target Review activation authority rejected command`. The Review packet/task deadline is not bounded by that activation cutoff and there is no renewal or re-binding authority for an already-open Review task.
- Impact: Before the repair, a case admitted shortly before the 30-minute activation cutoff could legitimately reach human review yet become impossible to decide after the cutoff; restarting services or changing the decision payload could not recover it.
- Verification evidence: Activation `p9act.v1.38b9240142f3ba137044b7acbc62f887` was signed through `2026-09-21T16:16:59Z`. Fresh case `CASE_P9_6A89CB7B_1` completed Intake, Evidence, Hearing M2/E1/E2, Judge V1, Jury, Judge V2, human Review, and Outcome; Review task `hearing-review-task-5f30c533cf9b355d98367e9f795168ee` persisted the approved decision and the browser rendered execution event `ACT_00da7f3ccb72dfca936defec7235bffe`.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.15cfe8b30223eaade5de9283c3dc3171`; case `CASE_P9_6A89604E_3`; task `hearing-review-task-f2fbc7dfb197371d95fc4bc6458acbef`; packet `hearing-review-packet-114ce8ce795a3188a886ee5a7ec631ac`; API exception `target Review activation authority rejected command`; Java PID `112716`; request id `REQ_1d0e8f165da049cda93759b40756e08a`.

## P0-20260823-OUTCOME-ROUTE-USES-STALE-CASE-AUTHORITY

- Severity: P0
- Status: NOT_REPRODUCED_ON_RETEST
- Component: Frontend Review-to-Outcome navigation and case-route authority
- Confirmed fact: Fresh case `CASE_P9_6A89CB7B_1` completed human Review successfully and reached progress `6/6`, but the post-submit browser returned to `/disputes`. Clicking its `查看最终结果` entry opened the fresh case Draft history rather than Outcome, and directly navigating to `/disputes/CASE_P9_6A89CB7B_1/outcome` redirected the same tab to the old case route `/disputes/CASE_P9_6A89604E_3/intake?view=history`.
- Root cause and evidence: The initially observed redirect was not reproduced after the completed case projection settled. Without any routing source change, both direct navigation to the explicit fresh Outcome URL and the overview `查看最终结果` control preserved `CASE_P9_6A89CB7B_1` and rendered its execution event. No persistent stale-case substitution mechanism is currently confirmed.
- Impact: The initial observation suggested a possible cross-case navigation risk, but the authoritative result entry is reachable and correctly bound on immediate CDP retest.
- Verification evidence: CDP direct navigation and the overview control both ended at `/disputes/CASE_P9_6A89CB7B_1/outcome`, displaying `继续履约执行事件已发布` and event `ACT_00da7f3ccb72dfca936defec7235bffe`; the old case ID was absent from the resulting URL and visible content.
- Identifying metadata: observed 2026-08-23; fresh case `CASE_P9_6A89CB7B_1`; stale case `CASE_P9_6A89604E_3`; review task `hearing-review-task-5f30c533cf9b355d98367e9f795168ee`; requested route `/disputes/CASE_P9_6A89CB7B_1/outcome`; observed route `/disputes/CASE_P9_6A89604E_3/intake?view=history`.

## P1-20260823-JAVA-AGENT-WORKER-RESTART-RACES-GRAPH-READINESS

- Severity: P1
- Status: CONFIRMED_RECOVERED_UAT_VERIFIED
- Component: Local target-E2E Java role restart and Graph readiness startup gate
- Confirmed fact: After the Java API, control worker, and agent worker were restarted concurrently, the fresh Intake page for `CASE_P9_6A89E0EC_7` remained indefinitely in `正在生成` with its textarea disabled and no model stream output.
- Root cause and evidence: Agent worker PID `116944` reached `GraphTransportConfiguration.graphTransportBundle` before the Graph readiness document reported ready. `GraphReadinessHandshake.requireReadyDocument` threw `Graph readiness response was not ready`; Spring cancelled application startup and the agent worker exited. The Python Graph service subsequently reported healthy, but the failed Java worker was not automatically relaunched.
- Impact: Agent commands remain queued without an active AGENT worker while the frontend presents a misleading perpetual generation state.
- Verification evidence: After Graph readiness became healthy, restarting only the Java agent worker as PID `66284` resumed the same persisted Intake run without replay. Case `CASE_P9_6A89E0EC_7` then completed Intake, real-image Evidence, Hearing E2 freeze, Judge V1, jury review, Judge V2, human Review approval, and Outcome publication as event `ACT_f90941fd2d3939f9cb1f84058f7e28eb`.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_7`; activation `p9act.v1.f713fd806655f0dc32b5fa398f199588`; failed Java agent worker PID `116944`.

## P1-20260823-HEARING-LLM-RUNS-NOT-ACTOR-VISIBLE

- Severity: P1
- Status: CONFIRMED
- Component: Hearing AgentRun discovery and browser-visible SSE projection
- Confirmed fact: The completed fresh case `CASE_P9_6A89CB7B_1` persisted LLM-backed Hearing outputs for intake questions, intake synthesis, evidence requests, evidence synthesis, Judge V1, jury review, and Judge V2, but the browser could not receive their incremental text and displayed each durable result only after commit.
- Confirmed fact update: Fresh full-chain case `CASE_P9_6A8AC2C9_1` reproduced whole-message presentation for the evidence clerk, judge and jury outputs instead of incremental browser rendering.
- Root cause and evidence: Every inspected `AGENT_RUN_STARTED` descriptor for the automatic Hearing chain declares `stream_access=INTERNAL_SYSTEM_ONLY`, while `HearingCourtView.canConsumeHearingRunEvent` subscribes only when the authoritative descriptor declares `ACTOR_VISIBLE` and supplies a stream URL. The current case replay records the internal-only declaration for `HEARING_EVIDENCE_SYNTHESIS`, `HEARING_JUDGE_V1`, `HEARING_JURY_REVIEW`, and `HEARING_JUDGE_V2`; their durable room messages carry the corresponding run IDs but no browser-visible run was active.
- Impact: The frontend's existing delta pacing, retry reset, reconnect, and durable-message handoff logic is bypassed for the Hearing LLM chain, so users see long silent waits followed by whole-message replacement rather than genuine streaming output.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89CB7B_1`; operations `HEARING_INTAKE_QUESTIONS`, `HEARING_INTAKE_SYNTHESIS`, `HEARING_EVIDENCE_REQUESTS`, `HEARING_EVIDENCE_SYNTHESIS`, `HEARING_JUDGE_V1`, `HEARING_JURY_REVIEW`, `HEARING_JUDGE_V2`; frontend gate `streamAccess === ACTOR_VISIBLE`.

## P1-20260823-HEARING-REENTRY-REPLAYS-ENTRY-ANIMATION

- Severity: P1
- Status: CONFIRMED
- Component: Hearing room entry presentation state
- Confirmed fact: After a USER or MERCHANT has already entered a Hearing room once, a later entry still replays the staged room-loading animation instead of immediately presenting the established room history.
- Root cause and evidence: The exact frontend persistence boundary is not yet isolated; the repeated animation is observable on a second participant entry to the same completed Hearing room, so the current first-entry authority is not retained or not consulted when the page mounts again.
- Impact: Returning participants are forced through a synthetic first-entry sequence and temporarily cannot inspect the already durable Hearing transcript.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A8AC2C9_1`; route family `/disputes/{caseId}/hearing`; roles `USER` and `MERCHANT`.

## P1-20260823-OVERVIEW-EVIDENCE-VERIFICATION-ROUTES-TO-INTAKE

- Severity: P1
- Status: CONFIRMED
- Component: Dispute overview case-stage navigation
- Confirmed fact: From the dispute overview, selecting evidence verification as USER or MERCHANT navigates to the case Intake page rather than the Evidence page.
- Root cause and evidence: The exact route selector is not yet isolated; the observed destination is the Intake route for a case whose selected journey action is evidence verification, proving that the overview action and its destination are not bound to the same stage.
- Impact: Participants cannot enter the Evidence room from the primary overview action and may mistakenly continue editing Intake instead of reviewing or submitting evidence.
- Identifying metadata: observed 2026-08-23; route source `/disputes`; affected roles `USER` and `MERCHANT`; expected route family `/disputes/{caseId}/evidence`; observed route family `/disputes/{caseId}/intake`.

## P1-20260823-EVIDENCE-MULTIMODAL-FINAL-CONTRACT-REJECTED

- Severity: P1
- Status: CONFIRMED
- Component: Evidence image submission graph finalization
- Confirmed fact: In fresh browser UAT case `CASE_P9_6A89E0EC_1`, a 2.2 MB PNG was accepted into the USER staging area, the evidence batch command started, and the evidence officer streamed an acknowledgement before the run ended with `GRAPH_CONTRACT_REJECTED`. The evidence remained pending and no evidence catalog item was committed.
- Root cause and evidence: Python rejected the first model `EVIDENCE_OBSERVATION` with `EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE`. The v2 context assembled `source_unit_catalog` only from successfully parsed text before the asset loader ran, so this PNG had an empty catalog even though the loader later delivered its authorized, MIME-checked and hash-checked pixels to the model. The provider Schema still exposed `EVIDENCE_OBSERVATION` without a bound `source_unit_id` enum, allowing the model to describe visible pixels with an identifier that the live public-output policy necessarily rejected. The repair now creates deterministic `IMAGE_PIXELS` source authority only from a validated loader-issued manifest before configuring the provider Schema and public stream; if neither parsed text nor loaded pixels exists, the observation branch is removed from both contracts.
- Impact: Image evidence cannot complete model review or become submitted evidence in the observed path, so the multimodal evidence judgment cannot be consumed by later fact-level evidence coverage.
- Verification evidence: All 17 focused Evidence v2 workflow tests pass. The new positive regression proves a direct PNG with `parse_status=PENDING` receives one evidence-bound `IMAGE_PIXELS` source ID, a provider enum, and live-policy acceptance; the negative regression proves a material turn with no loaded or parsed source cannot generate an observation at all.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_1`; run `target-evidence-run:d48a4a816da5322fbcfa89489a24b4f0`; actor `user-local/USER`; route `/disputes/CASE_P9_6A89E0EC_1/evidence`; file `CASE_P9_6A89E0EC_1-air-purifier-multimodal-evidence.png`; public diagnostic `GRAPH_CONTRACT_REJECTED`; internal error `EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE`.

## P1-20260823-EVIDENCE-MULTIMODAL-ASSET-ENDPOINT-MISROUTED

- Severity: P1
- Status: CONFIRMED
- Component: Python Evidence asset loading and Java internal evidence content boundary
- Confirmed fact: A later fresh PNG batch for `CASE_P9_6A89E0EC_1` completed and committed, but its persisted verification manifest records `visual_input_status=FETCH_FAILED`; the model reported `VISUAL_UNREADABLE` and assessed only metadata and remarks instead of image pixels.
- Root cause and evidence: The running Python service configures `JAVA_API_SERVICE_URL=http://127.0.0.1:18080`, and `EvidenceAssetLoader` appends `/internal/evidence/{caseId}/{evidenceId}/content` to that shared base URL. With the same case, evidence ID, service identity and secret, port `18080` returns HTTP 404 while the Java API on port `8081` returns HTTP 200, `image/png`, and all `2,273,686` persisted bytes.
- Impact: Image evidence can appear successfully submitted and verified while the multimodal model never receives its pixels, so downstream evidence findings may be based only on filenames, metadata and party remarks.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_1`; evidence `EVIDENCE_3be0ebe31e8c46baa454f5726f572237`; batch `EVIDENCE_BATCH_e09b2496eeb247518ec0f0ae367a7c26`; run `target-evidence-run:1d733b9a0eaa3894ae44279592106734`; configured base `http://127.0.0.1:18080`; authoritative content base `http://127.0.0.1:8081`.

## P0-20260823-ABORTED-EVIDENCE-RUN-LEAKS-COMMAND-REVISION-RESERVATION

- Severity: P0
- Status: CONFIRMED
- Component: Target Evidence command admission and terminal AgentRun failure finalization
- Confirmed fact: After the first multimodal Evidence graph run for `CASE_P9_6A89E0EC_1` terminated with `GRAPH_CONTRACT_REJECTED`, the case projection reported no active graph run and process revision `9`, but submitting a distinct newly uploaded image batch returned HTTP 409 `expected process revision is already reserved by an active command`.
- Root cause and evidence: Command `evidence-submit:EVIDENCE_BATCH_ca7ea08b1ad14cfebe7d7f6b4683f620` remains persisted as `ORCHESTRATION_ACCEPTED` with `expected_process_revision=9` and `applied_at=null`; its `target_e2e_command_admission` row has no matching `target_e2e_command_completion` row even though logical run `target-evidence-run:d48a4a816da5322fbcfa89489a24b4f0` is terminally aborted. The active-reservation check therefore treats a terminally failed graph command as active indefinitely.
- Impact: A single terminal Evidence graph failure permanently prevents every later command at the same process revision, including a newly uploaded and otherwise valid multimodal evidence batch; the case cannot resume from its last durable checkpoint.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_1`; stuck command row `CMD_14d90351784b4f9f93a81323782a2ffd`; target admission `p9cmd.v1.8dbce479f3e74797a96b044c5a0e3c73`; failed run `target-evidence-run:d48a4a816da5322fbcfa89489a24b4f0`; blocked evidence `EVIDENCE_3be0ebe31e8c46baa454f5726f572237`; HTTP status `409`.

## P1-20260823-MULTIMODAL-EVIDENCE-BOUND-TO-TEXT-ONLY-MODEL

- Severity: P1
- Status: CONFIRMED
- Component: LiteLLM model binding for multimodal Evidence review
- Confirmed fact: The live Evidence asset loader successfully produced one authorized `IMAGE_PIXELS` content part from the persisted PNG, but both governed provider attempts terminated before any visible model output and the logical run ended `GRAPH_CONTRACT_REJECTED`.
- Root cause and evidence: The deployed model binding is `qwen3.7-max`. Alibaba Cloud's authoritative model contract identifies that alias as equivalent to `qwen3.7-max-2026-05-20` with text-only input, while vision is available on `qwen3.7-max-2026-06-08`. A direct same-proxy probe returned HTTP 200 and a complete SSE stream for text-only input, then HTTP 400 for the same model with the validated PNG `image_url`; the provider response stated `Unexpected item type in content`.
- Impact: Every correctly loaded image evidence item is rejected at the model boundary before pixel review, so image submissions cannot complete an Evidence graph run under the deployed model binding.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_1`; evidence `EVIDENCE_7ca808c1c6c34c37b4889055cd8d0353`; batch `EVIDENCE_BATCH_14634702825f4de289e588b3c908f731`; run `target-evidence-run:64301a2b137a3489b5bc8caac4fe4026`; deployed model `qwen3.7-max`; provider status `400`; provider code `invalid_parameter_error`.

## P1-20260823-EVIDENCE-SUBMISSION-INTERNAL-ERROR-AFTER-VISION-SWITCH

- Severity: P1
- Status: CONFIRMED
- Component: Evidence batch submission HTTP-to-orchestration boundary
- Confirmed fact: After switching the live model binding to `qwen3.7-max-2026-06-08`, a fresh 2,427,665-byte PNG upload completed with HTTP 201, but submitting that pending evidence as a one-item batch returned `INTERNAL_ERROR` before any new Evidence AgentRun was created.
- Root cause and evidence: The selected pre-existing Evidence case carried `current_deadline_at=2026-08-22T04:52:41.487632+08:00`, while the submission was attempted on 2026-08-23. `EvidenceSubmissionService` propagated that expired authority into the target `AcceptCaseCommand`; `CaseCommandService.accept` rejects any deadline that is not after acceptance time. The transaction rolled back before an Evidence batch or command was persisted, matching the database state and Java request trace `TRACE_93541f5a9be0cddbbe949127cee3d840`.
- Impact: An expired Evidence room presented an unclassified HTTP 500 instead of a domain deadline response. This failure did not exercise the vision-capable provider and does not establish a multimodal model failure.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A888D81_4`; evidence `EVIDENCE_929fbeefa2e34738853cb0b82c5049fb`; actor `user-local/USER`; idempotency key `evidence-batch-vision-model-switch-20260823-1`; request `REQ_e00871dae70c4a67b93207e9f04e43b0`; trace `TRACE_93541f5a9be0cddbbe949127cee3d840`.

## P1-20260823-RESPONDENT-MATRIX-LOCAL-FACT-REJECTED-INSTEAD-OF-BOUND

- Severity: P1
- Status: CONFIRMED
- Component: Intake respondent matrix finalization
- Confirmed fact: Fresh case `CASE_P9_6A89E0EC_2` completed both USER turns and the first MERCHANT turn. The second MERCHANT provider call also completed with a final frame and a complete result, but Java rejected formalization with `INTAKE_RESPONDENT_MATRIX_NEW_FACT_COLLISION`; command sequence 5 is `FAILED`, while all preceding commands are `APPLIED`.
- Root cause and evidence: `IntakeRespondentMatrixFreezer.derive` treats every proposal-local fact key as a new fact. When its deterministic category/target fingerprint already belongs to a parent fact, it throws `INTAKE_RESPONDENT_MATRIX_NEW_FACT_COLLISION` instead of binding that proposal row to the existing stable fact ID. The persisted attempt proves provider completion (`outcome=COMPLETED`, `final_frame_observed=true`, `last_sequence_no=43`) before this Java-only rejection.
- Impact: A structurally complete respondent turn cannot commit when the model expresses an existing fact with a proposal-local key. The appended rejection event makes the public stream appear non-contiguous/double-terminal to the UAT observer and blocks the case before Evidence opening.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_2`; command `intake-message:d035133ae4b6326089bfd03a4482e114`; logical run `target-intake-run:d035133ae4b6326089bfd03a4482e114`; attempt `target-intake-attempt:d035133ae4b6326089bfd03a4482e114:1`; process revision 4.

## P1-20260823-FIVE-ROUND-UAT-EVIDENCE-OPENING-KEY-UNBOUND

- Severity: P1
- Status: CONFIRMED
- Component: Canonical five-round backend UAT harness
- Confirmed fact: `CASE_P9_6A89E0EC_3` successfully committed all six Intake commands, transitioned to an active Evidence epoch at process revision 6, and persisted the final Intake matrix. The UAT then exited as `unexpected` immediately after the empty Evidence message baseline and before submitting an Evidence opening command.
- Root cause and evidence: `five-round-intake-api-uat.py::execute` constructs `evidence_opening_key` from local variable `suffix`, but that function never initializes `suffix`. The failure position exactly matches the completed-stage boundary: `evidence_message_baseline` is recorded, while `evidence_opening` is absent and the database contains no Evidence command or AgentRun.
- Impact: A healthy backend transition is reported as an unexpected UAT failure before the Evidence agent can be invoked, preventing backend multimodal verification.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_3`; process revision 6; current room `EVIDENCE`; harness `.local-dev/five-round-intake-api-uat.py`.

## P1-20260823-INTAKE-MODEL-OUTCOME-REJECTED-BY-DUPLICATE-SEMANTIC-GATE

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Intake ordered-output materialization and party-state validation
- Confirmed fact: In fresh frontend case `CASE_P9_6A89E0EC_4`, the first MERCHANT response produced a complete visible reply, but command `intake-message:63176c730b1b3dedb4482594ddb8c97a` ended `FAILED` with public diagnostic `GRAPH_STREAM_PROTOCOL_REJECTED`; the logical run was aborted after one provider call and no final frame committed.
- Root cause and evidence: The typed provider result declared `ready_for_next_step=true`, `admission_recommendation=ACCEPTED`, `remark_status=WAITING_FOR_REMARK`, no blocking gaps, and `total_score=85`, while its six bounded score components summed to 80. The dossier materializer correctly canonicalized the durable score to the six-component sum, but `_validated_party_intake_entry` then independently required that canonical score to be at least 85 whenever the model selected the ready branch and raised `INTAKE_PARTY_STATE_OUTCOME_CONFLICT`. Python logged the rejection at `adapt_intake_baseline_output` after provider completion; graph attempt 1 records `provider_call_count=1`, `GRAPH_STREAM_INTERRUPTED/STREAM_INTERRUPTED`, despite the immutable command granting two provider attempts.
- Impact: A structurally valid model result is rejected by a second backend semantic decision after generation, so the current Intake turn cannot commit and the existing final-Schema regeneration path cannot run.
- Identifying metadata: observed 2026-08-23; activation `p9act.v1.f713fd806655f0dc32b5fa398f199588`; case `CASE_P9_6A89E0EC_4`; actor `merchant-local/MERCHANT`; logical run `target-intake-run:63176c730b1b3dedb4482594ddb8c97a`; graph thread `grt.v1.01a02b34d50f789c8cbab6fa6d5fd5dd`.

## P1-20260823-FAILED-INTAKE-EVENT-BINDING-CREATES-GRAPH-SEQUENCE-GAP

- Severity: P1
- Status: FIXED_BACKEND_VERIFIED
- Component: Intake failed-turn recovery across Domain event binding and Graph checkpoint state
- Confirmed fact: After the first MERCHANT command in `CASE_P9_6A89E0EC_4` failed after one provider call, a second authenticated MERCHANT message was accepted into the room transcript and admitted as a new command, but the new Graph attempt aborted before any provider call with `INTAKE_EVENT_SEQUENCE_INVALID` and public diagnostic `GRAPH_STREAM_PROTOCOL_REJECTED`.
- Root cause and evidence: `JdbcIntakeGraphBindingStore.allocateEvent` allocates from the maximum private event binding, including the event already bound for a terminally failed Graph command. The failed event therefore retained sequence 2 while the committed Graph checkpoint remained at sequence 1; the next distinct message was immutably bound as sequence 3. Python `_apply_event` requires exact contiguity with the committed checkpoint and rejected 3 because it still expected 2. Graph attempt `target-intake-attempt:15795f8af3183f818659d649aca5700b:1` records `provider_call_count=0`, confirming the failure occurred before model invocation.
- Impact: Once an Intake command fails after its private event is bound, submitting a later participant message on the same private thread cannot resume from the last committed Graph checkpoint; each later allocation remains ahead of the expected sequence.
- Recurrence evidence: After the phase-Schema fix was loaded, `CASE_P9_6A8BF9E2_6` remained safely committed at USER source turn 2 while the earlier failed turn had already consumed the next private event binding. A new exact answer was persisted as room message sequence 5 and admitted as run `target-intake-run:62fb332437ef3aa3a76ce32873df2557`, but Python rejected it in `_apply_event` with `INTAKE_EVENT_SEQUENCE_INVALID` before any provider output. The run completed in under one second with only `attempt_started -> error`, proving that this recurrence is the same failed-turn recovery gap and not a failure of the narrowed provider Schema.
- Verification evidence: On 2026-08-24, the same USER thread recovered its two terminal uncommitted suffix slots without deleting or rewriting either failed binding. Run `target-intake-run:68980c6c75b13bf4b37570a19b0e9009` committed source turn 3 at logical event sequence 3 generation 2, superseding the immutable aborted generation 1 binding; run `target-intake-run:c390dc1a89fe3e6b8d1c38b36759f24d` then committed source turn 4 at sequence 4 generation 2, superseding the immutable failed generation 1 binding. The persisted phase reached `NO_EXTRA_REMARKS`, and USER Intake confirmation reached `COMPLETED`.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_4`; failed commands `intake-message:63176c730b1b3dedb4482594ddb8c97a` and `intake-message:15795f8af3183f818659d649aca5700b`; graph thread `grt.v1.01a02b34d50f789c8cbab6fa6d5fd5dd`; committed event sequence 1; bound event sequences 2 and 3.

## P1-20260823-EVIDENCE-UAT-TTFB-OBSERVATION-USED-AS-FAILURE-GATE

- Severity: P1
- Status: CONFIRMED
- Component: Evidence streaming UAT observer
- Confirmed fact: Evidence opening command sequence 7 for `CASE_P9_6A89E0EC_3` is `APPLIED`; its AgentRun is `COMPLETED/COMMITTED` with final stream sequence 20. The UAT nevertheless stopped at `resumed_evidence_opening_stream/first_room_utterance_threshold_seconds` after measuring approximately 21.9 seconds.
- Root cause and evidence: `observe_evidence_agent_run` converts the recorded first-room-utterance duration into a hard upper-bound assertion. That assertion runs after live/replay trace consistency and visible-text equality have passed, so it reports a healthy committed run as a functional failure solely because latency exceeded the configured observation threshold.
- Impact: Backend correctness and multimodal verification are blocked by a performance observation even when the authoritative command, run and stream all commit successfully.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A89E0EC_3`; command `evidence-opening:59ee458067263b97a11a0e22e5e26be8`; run `target-evidence-run:1459a3b5884f366ba13a85b750aed1d1`.

## P1-20260823-FRONTEND-EXTERNAL-IMPORT-HTTP-405

- Severity: P1
- Status: CONFIRMED
- Component: Frontend external-dispute import HTTP boundary
- Confirmed fact: After a clean Docker restart with the frontend, Java API and Python agent services healthy, clicking “导入外部争议” on `/disputes` returned the visible error `服务返回了不可解析的响应（HTTP 405）`; no case appeared in the dispute list.
- Root cause and evidence: Docker exposed the static-only `frontend/server.mjs` directly on host port `5173`; that server accepts only `GET` and `HEAD`, while the actual API proxy lived only behind the separate nginx port. The browser therefore sent the correct `POST /api/disputes/import/simulate` to the static server and received its explicit HTTP 405 response before Java or model execution.
- Impact: A browser-driven fresh full-chain UAT cannot create its initial canonical case and therefore cannot enter Intake.
- Identifying metadata: observed 2026-08-23; page `/disputes`; actor `user-local/USER`; frontend `127.0.0.1:5173`; HTTP status `405`.

## P1-20260823-NGINX-STALE-JAVA-UPSTREAM-AFTER-RECREATE

- Severity: P1
- Status: RUNTIME_RECOVERED_ROOT_CAUSE_REMAINS
- Component: Docker Nginx-to-Java API proxy boundary
- Confirmed fact: After the Java API container was recreated, every browser `/api` request, including the resumed Intake confirmation, returned HTTP 502 while direct Java health on host port 8080 returned HTTP 200.
- Root cause and evidence: The running Java API resolves to `192.168.32.15`, but the Nginx worker continued proxying `/api` to its startup-resolved address `192.168.32.16`; Nginx logged repeated `connect() failed (111: Connection refused)` entries for that stale address, which is now assigned to the Java agent worker.
- Impact: Browser UAT cannot read or mutate any Java-backed application state even though Java itself is healthy; the Intake hash-protocol confirmation cannot reach application code.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; Nginx container `9852f5be2293`; Java API container `d16239c422cc`; HTTP status `502`.

## P1-20260823-INTAKE-CONFIRM-MATRIX-CONTENT-HASH-NONCANONICAL

- Severity: P1
- Status: FIXED_UAT_VERIFIED
- Component: USER Intake confirmation and initiator matrix freeze boundary
- Confirmed fact: Fresh browser-UAT case `CASE_d91f30df25134c20bc2a5dbea5f90979` completed its first formal USER turn, persisted the reply, reached 100% completeness, and enabled confirmation; `POST /api/disputes/CASE_d91f30df25134c20bc2a5dbea5f90979/intake/confirm` then returned HTTP 500.
- Root cause and evidence: The stored hash `6237a03e30241e2b7ed96e3e735e9d2d4317f602da3c7e8b81282a5dc29c0c31` exactly matches Python's historic sorted compact-JSON SHA-256 for the persisted matrix, while Java's RFC 8785/JCS recomputation is `982c2d5ca0a8f31957e83653b8334156e529dd8bd9ef808a1cbdce202d53d3da`; the Python producer and Java confirmation consumer therefore used different self-hash protocols. The same persisted fact rows contain null `evidence_coverage_status`, while the Java initiator freeze contract requires `PENDING_EVIDENCE_REVIEW`.
- Impact: The accepted USER room message remains readable, but USER Intake cannot be frozen or handed to MERCHANT Intake, so Evidence, Hearing, Review, and Outcome cannot proceed for the fresh case.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; route `/api/disputes/CASE_d91f30df25134c20bc2a5dbea5f90979/intake/confirm`; exception `IntakeFinalizationRejectedException`.

## P1-20260823-INTAKE-HASH-MIGRATION-PROJECTION-STATE-ORDERING

- Severity: P1
- Status: FIXED_FOCUSED_AND_UAT_VERIFIED
- Component: Intake legacy-matrix migration persistence boundary
- Confirmed fact: After the legacy matrix passed hash migration and the confirmation request reached Java, the same case returned HTTP 500 with `IllegalStateException: intake result cannot be refreshed from case status INTAKE_PENDING`.
- Root cause and evidence: `IntakeMatrixLifecycleService.freezeInitiatorIfPossible` invokes `persistProjection`; that method calls `FulfillmentCaseEntity.refreshIntakeResult` before the branch confirmation has advanced the case from `INTAKE_PENDING`, and the entity state machine rejects that ordering.
- Impact: A valid legacy matrix can be normalized to the unified hash protocol in memory but cannot commit the initiator freeze, so the case cannot enter MERCHANT Intake.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; route `/api/disputes/CASE_d91f30df25134c20bc2a5dbea5f90979/intake/confirm`; stack `IntakeMatrixLifecycleService.java:71/224`, `FulfillmentCaseEntity.java:511`.

## P1-20260823-INTAKE-LEGACY-REMARK-SOURCE-OMITTED

- Severity: P1
- Status: CONFIRMED
- Component: Respondent Intake request assembly and legacy handoff-remark authority upgrade
- Confirmed fact: The second MERCHANT response in `CASE_d91f30df25134c20bc2a5dbea5f90979` was persisted as room message `MESSAGE_6b962ef6ce5c464e81a5edbf7ab22aee`, but the following AgentRun failed with public diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`; no agent reply or updated dossier committed.
- Root cause and evidence: The previous USER Intake state is already `WAITING_FOR_REMARK` and cites real room message `MESSAGE_41c500ee11b9417c96cc2a2a61ff06a3`. The next MERCHANT request omits that message from every transcript supplied to Python: `recent_dialogue_messages` contains only sequences 4-6, while `initiator_statement_transcript` is rebuilt from the MERCHANT session memory using synthetic IDs `INTAKE_TURN_1/2`. During legacy handoff partition upgrade, `_participant_message_text_for_source` therefore cannot resolve the persisted USER source and raises `INTAKE_HANDOFF_REMARK_SOURCE_UNAVAILABLE` with `legacy ready authority cannot be upgraded without its participant message`. Python maps this deterministic state/assembly failure to `AGENT_OUTPUT_SCHEMA_INVALID`.
- Impact: A valid respondent answer cannot produce the next Intake dossier once the frozen initiator state carries legacy ready/remark authority; the public error incorrectly presents the failure as invalid model output.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; run `AGENT_RUN_ce8ecdee008b4af396be83356a95b11b`; missing source `MESSAGE_41c500ee11b9417c96cc2a2a61ff06a3`; persisted respondent message `MESSAGE_6b962ef6ce5c464e81a5edbf7ab22aee`; internal code `INTAKE_HANDOFF_REMARK_SOURCE_UNAVAILABLE`.

## P1-20260823-EVIDENCE-V2-ROLE-PROMPT-PATH-MISSING

- Severity: P1
- Status: CONFIRMED
- Component: Evidence opening prompt profile resolution
- Confirmed fact: Opening the Evidence room as MERCHANT and then USER in `CASE_d91f30df25134c20bc2a5dbea5f90979` produced immediate `INTERNAL_ERROR` failures before either evidence officer response was committed.
- Root cause and evidence: `PromptComposer` binds the Evidence node base template to `evidence_turn_v2.md` and derives strict role paths `evidence_turn_v2.merchant.md` and `evidence_turn_v2.user.md`. Neither source nor the running Python container contains those files; only the legacy profile files `evidence_turn.merchant.md` and `evidence_turn.user.md` exist. Python raised `PromptResourceError` for both missing v2 role templates within about 100 ms, before any model call.
- Impact: Every role-specific Evidence opening fails deterministically, so Evidence guidance and subsequent evidence-room model interaction cannot run even though Intake can transition into the room.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; MERCHANT run `AGENT_RUN_ce4205f95cd248a192914e9af6ba1c5d`; USER run `AGENT_RUN_f5eb891a013b403e983e97e93c8e2340`; missing resources `/app/app/agents/prompts/evidence_clerk/evidence_turn_v2.merchant.md` and `/app/app/agents/prompts/evidence_clerk/evidence_turn_v2.user.md`.

## P1-20260823-TEMPORAL-WORKERS-STARTUP-PROBE-RESTART-LOOP

- Severity: P1
- Status: CONFIRMED
- Component: Java CONTROL/AGENT worker startup readiness
- Confirmed fact: During the same UAT window, the Java AGENT and CONTROL worker containers repeatedly exited and restarted; observed restart counts continued rising from 16/15 to at least 26/24 respectively while the API and Python services remained running.
- Root cause and evidence: Each worker starts its factory and immediately executes a workflow on its own protocol task queue as a mandatory startup probe. The probe repeatedly remained incomplete for the shared 30-second startup budget, causing `TemporalWorkerConfiguration.requireStartupProbe` to throw `Temporal worker startup probe timed out`; Spring then aborted the ApplicationContext, and Docker `restart: unless-stopped` relaunched the container.
- Impact: Command workers are intermittently absent during UAT, creating queued or delayed orchestration and adding service instability independently of the deterministic Intake and Evidence failures above.
- Identifying metadata: observed 2026-08-23; containers `order-fulfillment-dispute-system-java-agent-worker-1` and `order-fulfillment-dispute-system-java-control-worker-1`; task queues `agent-execution`, `case-control`, and `room-control`; exception site `TemporalWorkerConfiguration.java:545`.

## P0-20260823-FULL-CHAIN-UAT-FELL-BACK-TO-LEGACY-RUNTIME-LANE

- Severity: P0
- Status: CONFIRMED
- Component: Post-restart frontend/API runtime binding for full-chain UAT
- Confirmed fact: Earlier successful full-chain cases `CASE_P9_6A89CB7B_1` and `CASE_P9_6A89E0EC_7` were claimed by an active Target-E2E activation and executed Intake, Evidence, and Hearing as `target-*-run` records over `agent-stream.v3`. The current browser-UAT case `CASE_d91f30df25134c20bc2a5dbea5f90979` has no Target case claim and every run is an `AGENT_RUN_*` record over legacy `agent_stream.v1`.
- Root cause and evidence: The Target runtime descriptor and unexpired activation remain on disk, but the Target source API formerly owned on port 8081 is no longer listening after the computer restart. The browser on port 5173 is now served by the standard Docker Nginx and routed to the Docker Java API on port 8080. That API is running only with profiles `local,api`, `APP_TEMPORAL_WORKER_ENABLED=false`, legacy epoch selection, and no `APP_TARGET_E2E_*` activation environment. Its Compose project is `order-fulfillment-dispute-system`, while the preserved Target runtime declares execution lane `TARGET_E2E_CANDIDATE` and Compose project `local_target_source`.
- Impact: The requested full-chain UAT is not exercising the previously verified candidate protocol. It silently enters the partially migrated legacy Intake/Evidence implementation, where deterministic protocol defects block the case before evidence submission; results from this case cannot establish candidate full-chain health.
- Identifying metadata: observed 2026-08-23; current case `CASE_d91f30df25134c20bc2a5dbea5f90979`; preserved activation `p9act.v1.f713fd806655f0dc32b5fa398f199588`; current API profiles `local,api`; current ports `5173 -> Docker Nginx`, `8080 -> Docker Java API`; absent listener `8081`.

## P1-20260823-FAILED-RESPONDENT-RUN-DID-NOT-BLOCK-INTAKE-COMPLETION

- Severity: P1
- Status: CONFIRMED
- Component: Legacy Intake completion and Evidence transition authority
- Confirmed fact: The latest MERCHANT response was persisted, but its AgentRun failed before updating the dossier. The persisted dossier remains version 4 with quality 85, `ready_for_next_step=false`, `admission_recommendation=NEED_MORE_INFO`, and source turn 1. Nevertheless, both party completion rows were accepted, Intake was closed, and the case transitioned to `EVIDENCE_OPEN`.
- Root cause and evidence: Legacy completion records only role completion and deadline state. `IntakeProgressService.completeRespondent` does not require the latest participant message to have a committed AgentRun or require the current dossier to be ready; Evidence access then checks only case stage plus the two completion rows. Database state therefore permits a failed, uncommitted last turn and its stale dossier to become the Evidence handoff authority.
- Impact: The downstream case matrix omits the detailed second MERCHANT answer even though the room transcript contains it. Continuing this case would build Evidence and Hearing context from stale Intake state and produce an invalid full-chain UAT result.
- Identifying metadata: observed 2026-08-23; case `CASE_d91f30df25134c20bc2a5dbea5f90979`; failed run `AGENT_RUN_ce8ecdee008b4af396be83356a95b11b`; persisted message `MESSAGE_6b962ef6ce5c464e81a5edbf7ab22aee`; case state `EVIDENCE_OPEN`; dossier version `4`.

## P0-20260823-REVIEW-APPROVAL-NOT-DELIVERED-TO-OUTCOME-WORKFLOW

- Severity: P0
- Status: CONFIRMED_REPAIR_INCOMPLETE
- Component: Target Review decision delivery and Outcome completion authority
- Confirmed fact: Fresh Target-lane cases `CASE_P9_6A8A9B5E_1`, `CASE_P9_6A8A9B5E_2`, post-repair case `CASE_P9_6A8A9B5E_3`, and authority-coordinate-repaired case `CASE_P9_6A8AC2C9_1` completed Intake, Evidence, Hearing V1/Jury/V2 and human Review; their browsers rendered published execution events while their action records remained `RUNNING` with attempt count 1 and no outcome operation, receipt, completion fact, or process projection row.
- Root cause and evidence: The first parent history accepted and durably routed `review-decision:95ed0fd...`, then failed its Update with `TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED`; its Outcome child received no decision Update because `TargetTypedRoomCaseProcessDispatcher.ReviewHandle` invoked child Updates directly and Temporal Java SDK 1.35 rejects workflow-to-workflow Update calls (`Update is not supported from workflow to workflow`). The second fresh history recorded change marker `target-review-outcome-child-update-activity-v1=1`, scheduled `AcceptTargetReviewDecisionOnOutcomeChild`, and the exact Outcome child accepted the Update request but returned `accepted=false`. Its start bound the frozen action ref `review-packet:hearing-review-packet-106f841cbda43d50bc41389458376fc2:action`, while the APPROVE receipt carried approved action ref `approval:APPROVAL_3ca2a3071e6745adb5a0cc93742f0d82:action` with the same action hash; `OutcomeRoomWorkflowImpl` requires APPROVE to preserve both the frozen ref and hash and therefore rejected the receipt before the kernel revision advanced. In the third post-repair history the child accepted the preserved ref/hash and advanced far enough to schedule `CompleteTargetOutcome`, but the `room-control` worker rejected every attempt because that Activity type was not registered; Temporal reports attempt 9 pending with known types limited to `PrepareIntakeInfrastructure` and `DescribeTemporalWorker`. After the Activity registration was loaded on `room-control`, worker `24784` received both pending completion attempts, and both failed at `outcome_process_projection` insertion because PostgreSQL trigger `enforce_target_temporal_outcome_projection_authority()` rejected the supplied `decision_request_hash` as invalid. In `CASE_P9_6A8AC2C9_1`, Review persistence and the active Review epoch agree on epoch `0`, fence `4`, and process revision `35`, so the previous request-authority mismatch is absent; the Outcome workflow then failed `CompleteTargetOutcome` because the adapter copied the 87-character workflow/tool build pin into the independently bounded `adapterVersion`. After that constraint was cleared, the same Workflow run exposed two pending `CompleteTargetOutcome` Activities and PostgreSQL rejected their operation rows with `Outcome operation approval binding is invalid`; worker identity `22608@LAPTOP-VJM9RQM6` recorded attempts 15 and 13 on Activity IDs `415c886e-2bb5-3dbc-8deb-d4449c7fc58d` and `935ae029-0aef-3dd2-857d-7e21b88ed690`. After the approval identity hash and approved action snapshot hash were bound separately, both Activities advanced past operation reservation and failed while updating `action_record`: PostgreSQL JDBC cannot infer a SQL type for the supplied `java.time.Instant`; worker `49844@LAPTOP-VJM9RQM6` recorded that failure at attempts 19 and 17. After explicit JDBC timestamps were loaded, Activity `415c886e-2bb5-3dbc-8deb-d4449c7fc58d` persisted enough progress to reach case closure and then failed with `case is not ready for closure`, while duplicate Activity `935ae029-0aef-3dd2-857d-7e21b88ed690` failed the initial epoch revision check as stale. After the explicit execution-state transition was loaded, the case advanced through execution and closure, but the retry paths failed because a uniqueness check advanced a JDBC `ResultSet` to its end before reading the retained row; worker `38724@LAPTOP-VJM9RQM6` recorded `查询结果指标位置不正确` on both pending Activities. After the Outcome cursor reads were corrected, both Activities reached the terminal persistence transaction and failed because the exact Review command-admission join returned no row; worker `51012@LAPTOP-VJM9RQM6` recorded `target Outcome command admission is absent or ambiguous` at attempts 29 and 27. The rejected row has outer durable case-command request hash `f29cfba256e90c6c49256bb7c8da3e80ae853648584fd71b405cecc46bc8c6a7`, while the admitted Review graph material has graph request hash `6b3a36b65aa60bd673ab267653edeeaf4f40f04a39f23402a9fcb0cb8cdd53c9`; `CaseCommandRequestHasher` hashes the authorized `AcceptCaseCommand` and actor, whereas `CanonicalTargetRoomCommandMaterializer.graph` hashes the graph command without its `request_hash`, so the Outcome and non-execution authority queries compare distinct hash domains. The terminal Outcome chain is not complete.
- Root cause and evidence update: After the cross-domain request-hash comparison was removed, Activity `415c886e-2bb5-3dbc-8deb-d4449c7fc58d` passed command admission and failed in `terminalize` because its SQL references nonexistent `case_room_epoch.writer_activation_status`; worker `52728@LAPTOP-VJM9RQM6` recorded the failure at attempt 33.
- Root cause and evidence update: The repaired Outcome child run `f09fdd6b-c960-4109-806e-a6d950814655` completed at 2026-08-23T11:19:38Z with phase `EVALUATED`, while its parent CaseProcess workflow remained `RUNNING`; one second later the parent `acceptCommand` Update surfaced the previously retained `TARGET_TYPED_ROOM_POST_ROUTING_COMPLETION_FAILED` promise failure even though the child and all terminal database facts had completed.
- Root cause and evidence update: The failed parent Update retained the Review child handle at terminal coordinates `process_revision=41` and `room_revision=6`. Manual recovery loaded the same durable terminal receipt at `41/6`, but `CoordinateHandle.advanceToTerminal` accepted only strictly greater coordinates and rejected that exact replay before the parent could adopt it.
- Verification evidence: The focused terminal-coordinate regression passed. After loading the change and signaling the blocked sequence once, parent CaseProcess run `588d6b99-1173-491a-8514-e1f7ba70b613` completed; the process projection is `TERMINAL` at revision `41`, Review epoch `0` is `TERMINAL` at `41/6`, the Review command is `APPLIED`, and the case has exactly one operation, one receipt, one command completion, and one copy of each of the four Outcome completion facts. Direct CDP verification rendered `驳回诉求已完成` for event `urn:target-outcome:no-effect:ACT_8941544cb048b73c21213451f620a764`.
- Impact: The interface reports a published execution event before the authoritative execution workflow has accepted and completed the decision, leaving downstream execution consumers without a durable completion fact.
- Identifying metadata: observed 2026-08-23; cases `CASE_P9_6A8A9B5E_1`, `CASE_P9_6A8A9B5E_2`, `CASE_P9_6A8A9B5E_3`, `CASE_P9_6A8AC2C9_1`; review tasks `hearing-review-task-98e53ecc1bd630f38e884de4c85d3c9b`, `hearing-review-task-022c418420ca3f80a94c8b0eb94a951c`, `hearing-review-task-4246b76f85383298972f1d551fbda3b3`, `hearing-review-task-eede46103c573f7ea9f5a7edf982ebf7`; actions `ACT_52b0055f5617fe602130e9df45c17be2`, `ACT_6a4f7587ec51da2ea808ee481592004f`, `ACT_815bd94da3d3f2cf7bd2391e37496520`, `ACT_8941544cb048b73c21213451f620a764`; Outcome runs `6a526a48-bea6-416f-9fe8-86a42a592fdc` and `f09fdd6b-c960-4109-806e-a6d950814655`; pending Activities `b35ff5d0-d632-3d92-bef2-4f312912dd0e`, `64adffde-0882-3ca9-8832-c51f46382f85`, and `415c886e-2bb5-3dbc-8deb-d4449c7fc58d` of type `CompleteTargetOutcome`.

## P2-20260823-INTAKE-VERIFICATION-FOCUS-MIXED-LANGUAGE-UNMAPPED

- Severity: P2
- Status: FIXED_FOCUSED_AND_BROWSER_VERIFIED
- Component: Intake verification-focus display mapping
- Confirmed fact: The MERCHANT Intake verification-focus list in fresh Target-lane case `CASE_P9_6A8AC2C9_2` displayed raw mixed-language phrases including `claimed third-party testing report details and methodology` and `logistics delivery confirmation timestamp matching ... receipt claim`.
- Root cause and evidence: The Intake view renders model-produced verification-focus prose through the current display mapper, but its phrase catalog does not recognize these embedded English clauses and therefore leaves them unchanged inside otherwise Chinese sentences.
- Verification evidence: The focused mapper regression passed with both observed mixed-language phrases. Vite HMR then updated the live Intake modal in the same case; CDP found both canonical Chinese actions and no remaining `claimed third-party`, `logistics delivery`, or `receipt claim` text.
- Impact: Review prompts are readable but contain implementation/model vocabulary instead of the intended human-facing Chinese labels.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A8AC2C9_2`; route `/disputes/CASE_P9_6A8AC2C9_2/intake`; actor `MERCHANT`.

## P1-20260823-FRESH-TARGET-INTAKE-INFRASTRUCTURE-PREPARATION-UNAVAILABLE

- Severity: P1
- Status: RUNTIME_RECOVERED_UAT_RESUMED
- Component: Fresh Target-lane Intake infrastructure preparation
- Confirmed fact: Browser import created fresh case `CASE_P9_6A8AC2C9_6`, but its initial Intake preparation and one explicit retry both ended with the visible message `intake infrastructure preparation is unavailable`; the case remained at Intake and could not enter its room. The same visible preparation failure recurred on 2026-08-24 immediately after the Python model service was restarted for `qwen3.8-max`, before the new case entered Intake.
- Root cause and evidence: In the original 2026-08-23 observation no AGENT worker remained after its startup-time readiness handshake was rejected, and restarting that worker after Python health recovered restored Intake. The 2026-08-24 recurrence had a different mechanism: worker PID `51848` was alive and polling, but Python `/health/model` returned HTTP 503 because the runtime requested `qwen3.8-max` while LiteLLM exposed only the 3.7 route; restarting the worker alone did not recover preparation.
- Impact: Each failed import remained outside Intake and exercised neither model output nor downstream stages. The 2026-08-24 route mismatch is tracked separately as `P0-20260824-QWEN38-LITELLM-ROUTE-MISSING`; after its focused verification, fresh USER-side UAT resumed.
- Identifying metadata: first observed 2026-08-23 on case `CASE_P9_6A8AC2C9_6`, actor `user-local/USER`, activation `p9act.v1.aa7bf7653d871e6558a30de80ce8eb21`; recurrence observed 2026-08-24 on route `/disputes`, Python PID `63028`, replacement worker PID `51848`, activation `p9act.v1.ae7eb72c010fd00e7f196fe13d87a30c`.

## P1-20260823-INTAKE-ORDERED-SECTIONS-LONG-TAIL

- Severity: P1
- Status: CONFIRMED / PERFORMANCE_INVESTIGATION_IN_PROGRESS
- Component: Target Intake structured provider output and governed stream projection
- Confirmed fact: In fresh browser-UAT case `CASE_P9_6A8AC2C9_6`, the first MERCHANT answer was submitted at `23:42:24`; the model began the public room reply at `23:42:28` and completed the exact follow-up question at `23:42:30`, but the browser remained in `接待官正在流式输出 / 正在整理` until the terminal frame at `23:47:14`.
- Root cause and evidence: The public reply occupied stream sequences 1-37 and completed in about 1.6 seconds after first output. The same single attempt then emitted ten `ordered_sections` serially: `CASE_MATRIX` at `23:42:44`, `CASE_STORY` at `23:42:48`, `PARTY_POSITIONS` at `23:42:53`, `CLAIM_AND_RESPONSE` at `23:42:59`, `DISPUTE_FOCUS` at `23:43:32`, `VERIFICATION_FOCUS` at `23:43:52`, `RISK_ASSESSMENT` at `23:44:37`, `MISSING_INFORMATION` at `23:45:13`, `HANDOFF_SUMMARY` at `23:45:44`, and `TURN_EVALUATION` at `23:47:10`; usage/final arrived at `23:47:14`. The completed attempt records `8,217` input tokens, `6,042` output tokens, `14,259` total tokens, and `290,103 ms` latency with no error.
- Additional evidence: A read-only model-only replay rebuilt the invocation from the exact pre-model checkpoint and command with the same model, schema, `temperature=0`, and thinking disabled. The replay again reported `8,217` input tokens, proving the same assembled input size, but produced `2,908` output tokens and completed model generation in `81.850 s`: first character `7.209 s`, public reply complete `8.972 s`, ten ordered sections complete at `22.335/24.205/29.364/34.724/44.552/50.516/57.811/64.232/67.372/81.503 s`, and final schema validation at `81.846 s`. It used one provider attempt, zero repairs, zero generation resets, and only `0.347 s` after the last visible structured delta for final schema validation.
- Impact: The user sees the requested answer within about six seconds but cannot continue for another four minutes and forty-four seconds because the input and confirmation controls remain locked until all hidden dossier sections and terminal validation finish.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A8AC2C9_6`; actor `merchant-local/MERCHANT`; logical run `target-intake-run:4aad3e7229cd37f9824865e68d1e33bc`; attempt `target-intake-attempt:4aad3e7229cd37f9824865e68d1e33bc:1`; recorded question `收到，已记录贵方关于用户现有检测信息不足以证明符合GB/T 18801-2022标准的具体理由，以及由平台协调双方共同选定具备资质第三方机构、事先书面确认复测条件并在确认后5个工作日内安排复测的替代方案，同时明确了复测达标与否对应的处理意见。请问贵方主张的‘页面宣传口径’具体对应哪些性能指标及测试条件？在平台协调复测期间，贵方是否同意先行暂停售后超时计时或保持当前售后单状态不变？`.
## P0-20260824-EVIDENCE-OPENING-FINALIZATION-REJECTED-6A8AC2C9-6

- Severity: P0
- Status: FIXED_FOCUSED_CHECK_PASSED
- Component: Evidence opening agent-run finalization
- Confirmed fact: Fresh UAT case `CASE_P9_6A8AC2C9_6` committed both parties' Intake and entered the MERCHANT Evidence room, where the opening stream exposed no committed clerk utterance and terminated with public diagnostic `AGENT_RUN_FINALIZATION_REJECTED`.
- Root cause and evidence: The Evidence v2 permissive `EvidenceV2Model.bind_missing_provider_fields` validator is inherited by `EvidenceFrameObjectV2`. When the deterministic leading `EvidenceRoomWelcomeFrameHeaderV2` model instance is passed into that wrapper, the validator treats the non-dict header as absent, replaces it with an empty header, and defaults its type to `ROOM_READINESS`. The live stream independently binds the same leading text to authoritative `ROOM_WELCOME`; all later frames match. The failed run therefore persisted public frame 1 as `ROOM_WELCOME` (`EFRM_31540660B6C51E60F00115E0`) while its proposal manifest declared frame 1 as `ROOM_READINESS` (`EFRM_1086C8BCE3D16E2FE6CB73E1`), and Java rejected the public/private authority mismatch. After removal of the coercive binding, the focused construction preserves sequence `1` and type `ROOM_WELCOME`; the three focused Python regression nodes passed on 2026-08-24.
- Impact: The Evidence room remains reachable and its upload controls are enabled, but the opening guidance did not commit, so the full-chain UAT cannot treat this stage as healthy.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_6`; role `MERCHANT`; room `EVIDENCE`.

## P1-20260824-EVIDENCE-FACT-EDGE-RELATION-CONTRACT-MISMATCH

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
- Component: Evidence V2 fact-edge formal projection persistence
- Confirmed fact: In fresh browser-UAT case `CASE_P9_6A8AC2C9_10`, the MERCHANT image evidence was read successfully and produced a visible multimodal assessment, but finalization failed with public diagnostic `AGENT_RUN_FINALIZATION_REJECTED`; the evidence batch remained pending and no formal fact edge committed.
- Root cause and evidence: The loaded Evidence observation projection supplied relation value `SUPPORTS` for evidence `EVIDENCE_b4b29c92e16545318ea0f5e7382fb415`. `JdbcTargetEvidenceFormalCommitPort.persistOrVerifyTurnProjection` copied that value unchanged into `evidence_fact_edge_v2.relation`, while PostgreSQL constraint `ck_evidence_fact_edge_v2_relation` accepts only `CONTENT_SUPPORTS`, `CONTENT_CONTRADICTS`, `CONTEXT_ONLY`, or `INCONCLUSIVE`. PostgreSQL rejected the insert at `JdbcTargetEvidenceFormalCommitPort.java:870`, and the formal committer converted the transaction failure into `AGENT_RUN_FINALIZATION_REJECTED`.
- Verification evidence: The formal observation binder now preserves the immutable model proposal while deriving canonical persisted relations: legacy/model values map to the four database relations and any unknown or missing relation degrades to `INCONCLUSIVE`. Focused test `TargetEvidenceFormalObservationBinderTest` passed on 2026-08-24, including legacy aliases, canonical values, unknown values, missing values, and input immutability.
- Impact: A successfully analyzed image evidence cannot complete its formal Evidence turn when the upstream relation vocabulary uses the legacy semantic value, so the active evidence batch and the full-chain UAT stop after the visible stream has already completed.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_10`; logical run `target-evidence-run:29a81b83fd1e3bb3b311258003abb141`; command `evidence-submit:EVIDENCE_BATCH_291aaba02558470fa51c8fbb90b58191`; evidence `EVIDENCE_b4b29c92e16545318ea0f5e7382fb415`; relation `SUPPORTS`; table constraint `ck_evidence_fact_edge_v2_relation`.

## P1-20260824-EVIDENCE-FAILED-FINALIZATION-LEAVES-NONRETRYABLE-SUBMISSION

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Evidence submission persistence, failed finalization, and browser retry state
- Confirmed fact: After the MERCHANT Evidence run in `CASE_P9_6A8AC2C9_10` ended with `AGENT_RUN_FINALIZATION_REJECTED`, the browser continued to show the same image in the pending batch and allowed another click on `提交本批给书记官`. The second POST returned HTTP 500 before creating any new `case_command`, `agent_run`, or `agent_run_attempt`.
- Root cause and evidence: The initial submission transaction persisted evidence `EVIDENCE_b4b29c92e16545318ea0f5e7382fb415` and batch `EVIDENCE_BATCH_291aaba02558470fa51c8fbb90b58191` as `SUBMITTED` before asynchronous agent-run finalization. Finalization later marked only case command sequence `13` as `FAILED` and logical run `target-evidence-run:29a81b83fd1e3bb3b311258003abb141` as `ABORTED`; it did not change the already submitted evidence or batch. The browser failure path retained its pre-submit catalog instead of refreshing it, so the stale item remained actionable. At `2026-08-24T05:34:23.005+08:00`, the retry reached `EvidenceSubmissionService.createSubmission` and failed at line `219` with `only pending evidence can be submitted`; the global handler exposed that domain-state rejection as HTTP 500.
- Impact: A formalization failure leaves durable submission state and visible retry state disagreeing. The user is offered a retry that cannot create a new model attempt, and the active Evidence stage cannot progress without a separate recovery path.
- Verification evidence: Focused frontend regression `reconciles a durably submitted batch after asynchronous finalization fails` passed on 2026-08-24. It confirms that an asynchronous failure followed by an authoritative `SUBMITTED` catalog removes the stale submit action while retaining the original failure notice.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_10`; actor `merchant-local/MERCHANT`; evidence `EVIDENCE_b4b29c92e16545318ea0f5e7382fb415`; batch `EVIDENCE_BATCH_291aaba02558470fa51c8fbb90b58191`; failed command sequence `13`; retry endpoint `/api/disputes/CASE_P9_6A8AC2C9_10/evidence/submissions`.
## P1-20260824-HEARING-M2-SCHEMA-INVALID-LEAVES-UI-GENERATING

- Severity: P1
- Status: PRIMARY_ROOT_FIXED_FOCUSED / TERMINAL_PROJECTION_OPEN / UAT_PENDING
- Component: Hearing M2 question generation, terminal failure projection, and courtroom UI state
- Confirmed fact: In `CASE_P9_6A8AC2C9_11`, the first Hearing run `target-hearing-run:0e67c659dd183a60bbc0bc17bf74c145` entered terminal `FAILED / UNCOMMITTED` with `AGENT_OUTPUT_SCHEMA_INVALID`, while the courtroom remained indefinitely on `生成问题` and continued to state that the intake officer was generating shared issues.
- Root cause and evidence: The exact frozen Hearing request persisted matrix hash `ff2517930b53691950cc441bbd0c8f3f89f0e33345b8c866e617fc7a52a13030` over raw JSON in which optional `claims.initiator_claim.requested_amount` was absent. `HearingIntakeQuestionsRequestV4` parsing preserved the stored hash but Pydantic's full dump invented `requested_amount: null`; the former Hearing validators hashed that expanded representation as `36199e1dfd415b8bd6b5409aef77052fc81231ddc8faa4d6abfb4140aa6e2992` and rejected the authority before any provider call. The run started at `2026-08-24T06:00:19.774152+08:00` and was marked failed at `2026-08-24T06:00:20.494689+08:00`. The separate persisted Hearing stage remained `RUNNING`, so at `2026-08-24T06:02:32+08:00` the browser still exposed no error dialog or recovery action and showed no question-answer form.
- Verification evidence: Five focused regressions pass: whole-number Java/JCS input, omitted optional fields through both Hearing validators, negative post-hash tampering, and omitted optional fields through TrialDossier V1 and V2. A read-only replay of the exact failed artifact now validates both Hearing entry checks with the original stored hash and confirms `requested_amount` remains unset. The terminal-stage failure projection has not yet been repaired or UAT-verified.
- Impact: The integrated UAT cannot advance from Hearing M2, and the visible courtroom state disagrees with the persisted terminal run state.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_11`; run `target-hearing-run:0e67c659dd183a60bbc0bc17bf74c145`; stage `HEARING`; diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`.

## P0-20260824-HEARING-PARTY-ANSWER-REVISION-RESERVATION-DEADLOCK

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Hearing party-answer command admission and durable submission projection
- Confirmed fact: In fresh browser-UAT case `CASE_P9_6A8AC2C9_12`, the MERCHANT submitted all four Hearing answers and the courtroom displayed them, but command sequence `14` remained `ORCHESTRATION_ACCEPTED` with no `applied_at`; its outbox row is `DELIVERED`, no Target command admission exists for the command, and `hearing_round_party_submission` contains no row for either party. A subsequent USER submission of all four answers was rejected before command insertion with `expected process revision is already reserved by an active command`.
- Root cause and evidence: The durable case is merchant-initiated (`initiator_id=merchant-local`, `initiator_role=MERCHANT`) and user-responded (`respondent_id=user-local`, `respondent_role=USER`). `HearingRoomWorkflowImpl.participantFor` instead assumes that every USER is the initiator and every MERCHANT is the respondent. It therefore constructed the MERCHANT command operation key with participant `user-local`, while the immutable timeline event and `hearing_flow_action` correctly bind participant `merchant-local`. `HearingFormalFinalizer` rejected that exact-key mismatch in `FormalizeTargetHearingPartyAction`; Temporal retried the Activity nine times and then failed room workflow run `f5175d8e-13e6-4f12-9d6f-46fb3a23bbf8`. The unresolved command continued to reserve process revision `18`, which caused the subsequent USER submission rejection.
- Impact: The first party's visible Hearing answer is not durably recorded, the second party cannot submit at all, and the full-chain UAT cannot advance to evidence review or adjudication.
- Verification evidence: The Hearing operation-key participant now comes from the authenticated command actor after exact bilateral-membership validation, the formal start loader reconstructs the real initiator/respondent ordering, and timeout roles resolve from the persisted case participant. The merchant-initiated positive/negative participant-binding regression plus the adjacent Hearing formalization and bootstrap suites passed: 24 tests, zero failures.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8AC2C9_12`; actor commands `MERCHANT` then `USER`; expected process revision `18`; command sequence `14`; flow stage `PARTY_ANSWERS_OPEN`.

## P1-20260824-HEARING-E1-EVIDENCE-REQUESTS-CONTRACT-REJECTED

- Severity: P1
- Status: REOPENED / UAT_FAILED
- Component: Hearing E1 targeted evidence-request generation
- Confirmed fact: Fresh browser-UAT case `CASE_P9_6A8AC2C9_16` committed both parties' four M2 answers and committed `HEARING_INTAKE_SYNTHESIS`, then the immediately following `HEARING_EVIDENCE_REQUESTS` run terminated `FAILED / UNCOMMITTED` with diagnostic `GRAPH_CONTRACT_REJECTED`; the courtroom remained at `定向补证` and created no E1 answer form.
- Root cause and evidence: The frozen Evidence dossier copied model-authored fact-link relation `SUPPORTS_CLAIM` into `fact_evidence_matrix.v3.links[0].relation`. `HearingEvidenceRequestsRequest` accepts only `CONTENT_SUPPORTS`, `CONTENT_CONTRADICTS`, `CONTEXT_ONLY`, or `INCONCLUSIVE`, so the governed invocation decoder rejected the immutable E1 input before any provider call. Replaying the exact 32,056-byte MinIO invocation through the production Pydantic request type produces one validation error at `evidence_dossier.fact_evidence_matrix.links.0.relation`; the failed attempt records zero provider/model/token fields and 390 ms latency. The earlier formal Evidence projection binder canonicalized only the database edge, while `EvidenceDossierFreezer.factLinks` independently copied the unbound relation from `agent_findings_json`, leaving the downstream dossier vocabulary inconsistent.
- Impact: The full-chain UAT cannot advance from the committed M2 matrix to evidence verification, dossier freezing, adjudication, review, or Outcome.
- Verification evidence: The formal Evidence projection and frozen dossier now share one canonical relation binder; the observed `SUPPORTS_CLAIM` value freezes as `CONTENT_SUPPORTS`. Focused freezer and formal-observation regression tests passed: 5 tests, zero failures.
- Regression fact: Fresh browser-UAT case `CASE_P9_6A8B917B_1` committed both parties' Evidence turns, all eight Hearing M2 answers, and `HEARING_INTAKE_SYNTHESIS`; the following `HEARING_EVIDENCE_REQUESTS` run then terminated `FAILED / UNCOMMITTED` with outer diagnostic `TARGET_E2E_GRAPH_PROTOCOL_REJECTED` at 2026-08-24 09:03 CST. No downstream Hearing action was attempted after this terminal failure.
- Regression root cause and evidence: The immutable E1 invocation and the persisted `hearing_evidence_requests.v1` proposal both pass their production Pydantic contracts, so neither model output nor fact binding caused the regression. Python then projected the validated terminal `public_message` as `agent-stream.v3` event `visible_delta` for node `hearing_evidence_requests`, while Java's frozen `HEARING` visibility policy remained empty and its v3 reader requires every `visible_delta` node/field pair to be allowlisted. Java accepted sequence 0 `attempt_started`, rejected the first 500-byte `visible_delta` at sequence 1 with `field=UNAVAILABLE`, and never reconciled the already stored proposal. The preceding Hearing intake stages use v3 public-frame events rather than `visible_delta`, which is why they passed under the same empty field policy.
- Impact update: The latest UAT reaches and commits Hearing M2 but cannot cross the E1 Python-to-Java stream boundary; the failed case is terminal and cannot contribute to the consecutive-success count.
- Regression verification evidence: The Python terminal projection test proves a validated non-frame Hearing `public_message` is emitted as ordered deltas before `final` (1 passed). The Java frozen-policy and target-client binding tests prove all five authorized Hearing public nodes accept only `public_message` under the production v3 reader, continue rejecting reasoning/unknown/cross-room fields, and leave REVIEW private (8 passed, zero failures).
- Identifying metadata: observed 2026-08-24; cases `CASE_P9_6A8AC2C9_16`, `CASE_P9_6A8B917B_1`; stage `定向补证`; operation `HEARING_EVIDENCE_REQUESTS`; diagnostics `GRAPH_CONTRACT_REJECTED`, `TARGET_E2E_GRAPH_PROTOCOL_REJECTED`; failed logical run `target-hearing-run:7676d9b1946632c5beb2b65cffb5d16f`.

## P1-20260824-RESPONDENT-INTAKE-REGENERATION-SCHEMA-INVALID

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Target Intake respondent turn structured generation and retry finalization
- Confirmed fact: In fresh user-initiated UAT case `CASE_P9_6A8B9CE8_3`, the MERCHANT second answer produced a complete public reply and updated visible dossier, but both the original generation and its full-regeneration retry failed terminal structured validation; the room ended with `AGENT_OUTPUT_SCHEMA_INVALID` and did not persist the turn.
- Root cause and evidence: Both generations produced internally consistent ready-turn metadata (`total_score=100`, no blocking gaps, `WAITING_FOR_REMARK`, `INVITE_OPTIONAL_REMARK`). Both failed because `CASE_MATRIX.summary_source_fact_keys` referenced fact-key bodies such as `E672...` and `MERCHANT_...` while the same generated matrix rows exposed the authoritative keys as `FACT_E672...` and `NEW_MERCHANT_...`; the strict matrix validator therefore found no matching summary source. The prompt prescribed the `FACT_` / `NEW_` namespaces generally but did not explicitly require this summary field to copy the complete row key verbatim. The retry log's other branch errors are union-schema alternatives, not additional defects. The frontend reset once and then projected the terminal schema diagnostic after the second identical namespace-prefix rejection.
- Impact: The respondent cannot complete Intake, both parties cannot enter Evidence, and the fresh full-chain run does not count toward the required consecutive UAT successes.
- Regression verification evidence: The focused provider-contract test accepts uniquely resolvable missing `FACT_` / `NEW_` namespace prefixes and still rejects an ambiguous body shared by both namespaces (1 passed). Replaying the exact second rejected generation through the updated respondent Schema now validates and binds all six summary references to keys present in that generation's fact rows.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8B9CE8_3`; actor `merchant-local/MERCHANT`; route `/disputes/CASE_P9_6A8B9CE8_3/intake`; diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`; node `intake_turn_case_detail`; response sizes `6448` and `7953` characters.
- Regression fact update: In repair-validation case `CASE_P9_6A8BF9E2_7`, MERCHANT message `MESSAGE_9d67144a56514626afa28de8d251208c` supplied only shipment and signed-delivery facts after an earlier direct conditional-retest position had already been persisted. Both provider generations classified the typed current-source `respondent_claim` as `NOT_ADDRESSED / NO_DIRECT_POSITION` while the independent cumulative `CLAIM_AND_RESPONSE.respondent_attitude` card retained `ALTERNATIVE_PROPOSED / RESPONDENT_DIRECT`; both generations ended at the same root Pydantic error `respondent display attribution must match the bound matrix claim`, and the logical run aborted with `AGENT_OUTPUT_SCHEMA_INVALID`.
- Regression root cause and evidence update: `IntakeRespondentRoomLlmOutputV3.bind_display_attitude_to_matrix_claim` treats the current-message claim delta and the cumulative display card as one source-authority value. It therefore rejects a valid state transition where the current message does not update respondent attitude but the durable dossier must retain the respondent's previously grounded direct position. The two generations passed the previous-phase `ASK_SUBSTANTIVE` lock and the incomplete-turn score/gap contract; legacy `total_score` was removed before validation and was not causal.
- Regression impact update: A respondent cannot add factual details without restating its already persisted remedy position; an exact provider regeneration repeats the same cross-field contradiction, so Intake aborts before the new facts can commit.
- Regression metadata update: observed 2026-08-24; case `CASE_P9_6A8BF9E2_7`; run `target-intake-run:6a578b37f8fd376cafbc5f3de6e8418e`; graph command `intake-message:6a578b37f8fd376cafbc5f3de6e8418e`; attempt `target-intake-attempt:6a578b37f8fd376cafbc5f3de6e8418e:1`; stream reset sequence `38`; terminal sequence `73`.

## P2-20260824-BACKEND-UAT-FIXED-ROUND-PREMATURE-CONFIRM

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Resumable backend full-chain UAT harness
- Confirmed fact: In `CASE_P9_6A8BF9E2_3`, all four MERCHANT AgentRuns completed, but the latest persisted actor state remained `NOT_READY` and the Intake officer still asked whether the missing promotional thermos had been shipped and why it was absent. The harness nevertheless called Intake confirmation and immediately attempted to open Evidence; Evidence correctly returned HTTP 403 because `respondent_status` remained `OPEN`.
- Root cause and evidence: The fixed four-turn fixture never answered the repeated thermos-shipment question, then treated the fourth completed AgentRun as equivalent to actor completion. The confirmation helper also returned after the command response without waiting for `respondent_status=COMPLETED`, so it printed `INTAKE_COMPLETE` while the authoritative status still reported `can_enter_evidence=false` and `room_phase=WAITING_PARTY`.
- Impact: A healthy Intake state machine is reported as an Evidence-opening failure, and the full-chain UAT stops before uploading the required one-TXT/one-image evidence pair.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8BF9E2_3`; MERCHANT runs `target-intake-run:10f6266b6c433190818e2619b0e4a936`, `target-intake-run:b0b7898f7aab334982a0768eaf7aa2dc`, and `target-intake-run:7c159a8b4c3d3ecea30298d7d53ad799`; Evidence response `FORBIDDEN`.
- Regression fact: A fresh retry imported `CASE_P9_6A8BF9E2_4` as “无理由退货被认定影响二次销售” with a 699元退货退款 dispute, while the harness supplied the prior delayed-delivery/300元 fixture text. The USER phase still reached terminal, but the MERCHANT officer correctly asked whether the merchant accepted the 699元 return and the repeated stale answer did not address that question.
- Regression root cause and evidence: The resume harness imported a randomized simulated case while its party-answer strings and Evidence claims were fixed to a different scenario. The case authority endpoint and persisted room transcript therefore disagreed before Evidence, so the run could not serve as a semantically valid UAT even though its individual AgentRuns completed.
- Regression impact: The UAT can pass structural checks with party statements that contradict the imported case, or remain `NOT_READY` while repeatedly answering an unrelated dispute; either outcome invalidates full-chain acceptance evidence.
- Regression metadata: observed 2026-08-24; case `CASE_P9_6A8BF9E2_4`; title `无理由退货被认定影响二次销售`; second MERCHANT run `target-intake-run:7ef39c8ebed234deb3e159c35c042d48`.
- Regression fact update: In fixed-fixture retry `CASE_P9_6A8BF9E2_5`, both actor phase chains followed the canonical air-purifier dispute, but the harness confirmed the USER with hard-coded `DELIVERY_DELAY/MEDIUM`. The authoritative case read changed from the imported fixture's `SPECIFICATION_MISMATCH/HIGH` to `DELIVERY_DELAY/MEDIUM` while retaining the air-purifier title and description.
- Regression root cause update: The resume confirmation payload carried a second scenario-specific constant independent of the imported case authority, so correcting party text alone did not make the UAT semantically closed.
- Regression impact update: The case can reach actor terminal states while its durable dispute classification contradicts its title, narrative, party statements, and evidence; `_5` is excluded from acceptance evidence.
- Regression metadata update: observed 2026-08-24; case `CASE_P9_6A8BF9E2_5`; USER terminal run `target-intake-run:d511fe0b87ae3819ba8df92986d79bf7`; MERCHANT terminal run `target-intake-run:3d2e6db961af30adbcfa20f8fbdd3ee4`.
- Regression fact update: In fixed-fixture case `CASE_P9_6A8BF9E2_7`, the MERCHANT opening plus six completed follow-up runs remained `NOT_READY`; each follow-up repeated the same combined answer while the Intake officer repeatedly requested the unanswered actual shipment time and signed-delivery state. The latest persisted score was 85 but `ready_for_next_step=false`, with two blocking gaps/questions, so the harness correctly refused confirmation but could not finish Intake.
- Regression root cause update: The canonical fixture already contains four ordered MERCHANT statements, and stable completed case `CASE_P9_6A8AC2C9_1` reached the terminal remark phase by submitting those statements in sequence. The current fresh harness instead collapses part of that information into one fixed `MERCHANT_INTAKE_ANSWER` and replays it on every substantive turn, so later questions receive no new facts and the actor cannot converge.
- Regression impact update: The projection-pending retry repair is validated, but `_7` is only a repair-validation case and cannot count toward the required consecutive full-chain success streak until the question-aligned input mechanism is restored.
- Regression metadata update: observed 2026-08-24; case `CASE_P9_6A8BF9E2_7`; MERCHANT runs `target-intake-run:34ed57ffc1ec30b295db2fd8e3e4973f`, `target-intake-run:38a65859ef5e3ab5b4ae370050c25fad`, `target-intake-run:3600f2861ba4389fb4cd5f3e85748fba`, `target-intake-run:79f543176e6135ba9116937e833178cb`, `target-intake-run:4780adf26ae833e69aaec79a52defe1c`, and `target-intake-run:4ed441470aa33ea49940871662ef0ddc`.

## P1-20260824-BACKEND-UAT-APPROVE-SENDS-FROZEN-PLAN

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Resumable backend full-chain UAT Review submission
- Confirmed fact: Case `CASE_P9_6A8BF9E2_6` completed Hearing through Judge V2 and opened Review, but the resume harness submitted `APPROVE` with a non-null `approved_plan` copied from the frozen packet and received HTTP 400 `INVALID_ARGUMENT` with `APPROVE must use the frozen AI decision`; no execution action was created by that request.
- Root cause and evidence: `.local-dev/resume-case-to-outcome.py:471-484` reads `packet.remedy` and always sends it as `approved_plan`. `ReviewDecisionPlanPolicy.java:47-52` defines the current contract oppositely: `APPROVE` must omit the plan so the server itself deep-copies the authoritative frozen remedy, while only `MODIFY_AND_APPROVE` accepts a submitted plan.
- Impact: A healthy Review boundary is misreported as an E2E product failure after all model-driven stages have completed, and the harness cannot reach execution or Outcome even though the frozen AI decision is valid.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8BF9E2_6`; request `REQ_27ea322f85a0422ca016b8f01051ac09`; trace `TRACE_21c155aad66646914c6e52f0118a7b74`; Review task was moved to `IN_REVIEW` before the rejected decision.

## P1-20260824-BACKEND-UAT-REEXECUTES-TARGET-HANDOFF-ACTION

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Resumable backend full-chain UAT Review-to-Outcome continuation
- Confirmed fact: After the corrected `APPROVE` request for `CASE_P9_6A8BF9E2_6`, the Target Review handoff persisted action `ACT_7c23f395bfb969072f52e1ba76583f26` as `TARGET_NO_EXTERNAL_EFFECT / SUCCEEDED`. The resume harness then separately called the legacy `/execution/execute` endpoint and received HTTP 403 `TOOL_EXECUTION_DENIED` for the same idempotency key.
- Root cause and evidence: `.local-dev/resume-case-to-outcome.py:496-502` unconditionally invokes the legacy execution API after every Review decision. The Target Hearing/Review contract already materializes and succeeds its no-external-effect action as part of the authoritative Review-to-Outcome handoff; the second execution path is neither the producer nor the owner of that action and correctly fails its containment guard.
- Impact: The harness reports failure after the approved Target action has already succeeded, stops before observing the asynchronous Outcome terminal facts, and can misclassify a healthy Target handoff as an execution defect.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8BF9E2_6`; approval `APPROVAL_2a35c01bbc75424aae699e3eefb0bbb9`; action `ACT_7c23f395bfb969072f52e1ba76583f26`; request `REQ_9492801e269b44ec81b8b81d7406cee3`; trace `TRACE_6dbbb4dce6991f83573078545c66d78e`.

## P1-20260824-BACKEND-UAT-INTAKE-MESSAGE-MISCLASSIFIES-PENDING-PROJECTION

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Resumable backend full-chain UAT Intake message submission
- Confirmed fact: In fresh case `CASE_P9_6A8BF9E2_7`, the USER's second generated Intake turn completed and persisted `WAITING_FOR_REMARK`, but the immediately following remark POST received HTTP 409 `CASE_STATUS_INVALID` with reason `TARGET_E2E_INTAKE_PROJECTION_PENDING`; the harness aborted instead of waiting for the formal projection.
- Root cause and evidence: `.local-dev/resume-case-to-outcome.py:161-177` delegates Intake messages to the generic request helper, which treats every non-2xx response as terminal. The same harness already recognizes this exact transient reason while confirming Intake at lines 227-237, but message submission lacks the equivalent bounded replay loop.
- Impact: A healthy asynchronous Intake turn is reported as a failed E2E, even though no invalid model output or domain rejection occurred; fresh full-chain runs can stop between two otherwise valid party messages.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8BF9E2_7`; failed stage `fresh_user_follow_up_3`; process revision `2`; request `REQ_dce7d4bd7f6846ffb1da0519f74354b5`; trace `TRACE_b80c2f790af6a9e7bb34ce0e28725579`.

## P1-20260824-BACKEND-UAT-WAIT-RUN-IGNORES-ABORTED

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED / UAT_PENDING
- Component: Resumable backend full-chain UAT AgentRun polling
- Confirmed fact: Direct MERCHANT follow-up run `target-intake-run:6a578b37f8fd376cafbc5f3de6e8418e` in `CASE_P9_6A8BF9E2_7` became terminal `ABORTED / AGENT_OUTPUT_SCHEMA_INVALID` at `2026-08-24T10:57:02Z`, but the UAT command continued polling without returning or reporting the diagnostic until it was manually interrupted. On 2026-08-25 the fresh USER opening run `target-intake-run:56b642d77d0b3f2a808814da8405851b` in `CASE_P9_6A8D1C10_1` was observed at legitimate intermediate status `RESULT_READY`; the same poller immediately raised a failure even though the run subsequently reached `COMPLETED / COMMITTED` with the same result-ready and committed attempt.
- Root cause and evidence: `.local-dev/resume-case-to-outcome.py:112-126` returns only for `COMPLETED` and treats every status outside `PENDING/RUNNING` as failure. This collapses the legitimate `RESULT_READY` transition into an error while also requiring terminal `FAILED/ABORTED` to be distinguished from nonterminal statuses.
- Impact: Full-chain UAT can either abort during a healthy formal-commit race or fail to expose a genuine terminal error correctly, preventing safe checkpoint continuation and obscuring the product state.
- Identifying metadata: observed 2026-08-24 and reopened 2026-08-25; original case `CASE_P9_6A8BF9E2_7`, actor `merchant-local/MERCHANT`, run `target-intake-run:6a578b37f8fd376cafbc5f3de6e8418e`, terminal diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`; reopened case `CASE_P9_6A8D1C10_1`, actor `user-local/USER`, opening run `target-intake-run:56b642d77d0b3f2a808814da8405851b`.

## P1-20260824-DEV-LOCAL-RESTART-TARGET-TOPOLOGY-CONFLICT

- Severity: P1
- Status: ENVIRONMENT_REPAIRED / UAT_PENDING
- Component: Local all-service restart orchestration
- Confirmed fact: Running `scripts/dev-local.ps1` from the candidate checkout stopped the prior TARGET source topology's Docker application services, recreated shared dependencies from the candidate-local `.env`, and then terminated with exit code 1 at line 373 while creating `.local-dev/java-control-worker.out.log`; Windows reported that another TARGET Control Worker still used the file. A subsequent authoritative `.local-dev/launch-source.ps1` activation reached Java API startup but failed `target Intake payload readiness` because MinIO returned `InvalidAccessKeyId`.
- Root cause and evidence: `scripts/dev-local.ps1` owns the ordinary 8080 local topology rather than the 8081 TARGET activation topology, so it neither reconciles TARGET ownership nor binds TARGET activation/JWKS/mTLS/build IDs. It also loaded the candidate-local `.env`; the recreated MinIO container's `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` exactly match that file and both differ from the root `.env` consumed by `.local-dev/launch-source.ps1`. The retained TARGET worker held the ordinary launcher's log path, while the split MinIO credential authority made the newly signed TARGET API fail closed before readiness.
- Impact: The local service set is left partially stopped, the new TARGET activation cannot initialize its immutable Intake payload bucket, no fresh full-chain UAT can begin, and a second blind ordinary-local invocation risks duplicate Temporal pollers plus repeated credential drift.
- Identifying metadata: observed 2026-08-24 at 19:19-19:31 CST; ordinary script `scripts/dev-local.ps1`; authoritative script `.local-dev/launch-source.ps1`; failing log `.local-dev/java-control-worker.out.log`; Java API PID `46848`; MinIO container `order-fulfillment-dispute-system-minio-1`; new activation `p9act.v1.4df66dd074abcf6b7decbeee91e93ff6`.

## P1-20260824-AGENT-RUN-RECOVERY-EXISTING-GLOBAL-TERMINAL

- Severity: P1
- Status: CONFIRMED / QUEUED
- Component: AgentRun V2 post-commit recovery scheduler
- Confirmed fact: After the fresh TARGET activation became healthy, the API recovery side effect repeatedly selected historical Hearing run `target-hearing-run:24afa4da7d3732b4ad7d2bfa8f116016` with persisted status `RUNNING`, then failed with `recovery candidate already has a global terminal event`; the exception was logged by both `PostCommitSideEffectExecutor` and the scheduled task while the API remained available.
- Root cause and evidence: The recovery candidate selection admits a run whose global terminal stream event already exists, while `JpaAgentRunLedger.requireRecoveryTerminalPosition` rejects that same state before `terminalizeV2RecoveryCandidate`. The selector and terminalization boundary therefore disagree on whether an existing global terminal event is recoverable.
- Impact: Historical stale-run reconciliation emits repeated scheduled-task errors and cannot converge that candidate; fresh requests remain serviceable, but any new run entering the same split state could lose automatic recovery progress.
- Identifying metadata: observed 2026-08-24 at 19:37 CST; API PID `75264`; activation `p9act.v1.c039c93da0c89267127b15c357ed4630`; run `target-hearing-run:24afa4da7d3732b4ad7d2bfa8f116016`; rejecting method `JpaAgentRunLedger.requireRecoveryTerminalPosition`.

## P0-20260825-INTAKE-PARALLEL-RUNTIME-INTEGRATION-INCOMPLETE

- Severity: P0
- Status: RESOLVED / DEPLOYED / UAT_VERIFIED
- Component: Intake exact-three parallel AgentRun runtime integration
- Confirmed fact: The technical V4 frame contract, exact-three staging, immutable READY artifacts, and Java assembly coordinator exist, but no production runtime path can yet execute that profile end to end. `ExecuteAgentRunRequest` and the canonical Intake materializer still admit only `agent-stream.v3`; the only configured execution gateway consumes legacy `AgentStreamEvent`; no V4 terminal append/replay owner exists; and the coordinator's trusted per-command context resolver has no production implementation or bean.
- Root cause and evidence: The parallel frame work was intentionally delivered in isolated slices before activation. `ExecuteAgentRunRequest` rejects non-v3 protocols, `CanonicalTargetIntakeMaterializer` creates V3 logical runs and requests, `DurableAgentRunExecutionGateway` owns one V3 accumulator/client path, while `TargetE2EIntakeParallelAssemblyCoordinator` only publishes technical READY artifacts and explicitly does not append FINAL or advance RESULT_READY. The frozen initial snapshot's `current_dossier` is replayed on later turns, so it cannot serve as authoritative previous-turn context for the new coordinator.
- Additional confirmed fact: A newly admitted attempt currently persists `last_sequence_no=0`, while the partitioned delivery high-watermark starts at `-1` and advances only from the next contiguous sequence. Because V4 has no V3 `attempt_started` prelude, the existing `last_sequence_no + 1` allocation would emit sequence `1` first and the V4 writer would reject it with the durable high-watermark still at `-1`. The existing database check also rejects the required empty-attempt V4 baseline of `-1`.
- Additional confirmed fact: The first profile selector implementation used only an exact-parallel boolean. A request carrying the reserved parallel agent profile or output schema but missing another required authority field could therefore evaluate as non-parallel and be delegated to the legacy V3 gateway when its protocol was also downgraded to V3. The initial local-reconciliation API also accepted an in-memory assembly result without first reloading and revalidating the immutable READY artifact.
- Additional confirmed fact: The first parallel Frame implementation emitted `FRAME_GENERATION_RESET` and the replacement `FRAME_STARTED` immediately when provider schema repair began, without first emitting an interruption for the superseded generation. Java's staging authority accepts a replacement generation only when the current slot is already `FAILED` or `AMBIGUOUS`; the observed event order therefore leaves the slot `STARTED` and makes the first legitimate lane-local schema repair fail before the replacement Frame can be admitted.
- Additional confirmed fact: A complete child checkpoint retained only the replacement generation's terminal projection and omitted the superseded generation's interruption/reset lineage. If Java durably observed generation 1 but missed the live reset before a transport failure, checkpoint replay started directly at generation 2, which cannot advance the Java slot and makes the completed provider result unrecoverable without another call.
- Additional confirmed fact: Parallel child checkpoints are deliberately written through `FencedPostgresSaver` under the active Graph command lease, but the parallel lane emits only technical Frame events and never creates a Python `RoomGraphResult`. The existing Graph command lifecycle can release that lease successfully only after storing a terminal result; otherwise a successful exact-three Frame execution leaves the Python command and attempt `EXECUTING` until lease expiry. Treating success as abort/cancel would corrupt replay semantics, while manufacturing a business terminal result in Python would create a second finalization authority.
- Additional confirmed fact: The current command-level provider-call budget is capped at two, while exact-three parallel execution requires at least three first-generation calls and up to six calls when each lane consumes its single schema-repair generation. Provider intent accounting is aggregated at the command attempt, so the parallel lane exhausts the current budget before all three first-generation Frames can execute.
- Additional confirmed fact: The parallel Frame model boundary currently emits `FRAME_INTERRUPTED(retryable=true)` for every escaping exception. This incorrectly grants retry authority to deterministic permission, prompt/Schema binding, checkpoint-authority, and canonical-output failures; only the reviewed transient provider-stream interruption and the existing in-band schema-generation reset may be retryable.
- Additional confirmed fact: A fully sealed parallel command can be replayed from the immutable `TECHNICAL_COMPLETED` record, but a process loss while the command remains `EXECUTING` cannot reach the existing complete child checkpoints: generic admission classifies the persisted attempt as requiring a new Agent attempt, while this V4 lane intentionally permits only attempt 1. Production activation therefore still requires a parallel-specific same-attempt recovery fence rather than a relaxation of the legacy V3 acquire path.
- Additional confirmed fact: Parallel model-context construction requires a trusted `room_id`, but the current `RoomGraphCommand`, frozen Intake snapshot and source event do not carry that field. Existing tests supply a handwritten value, so production cannot build the same context without inventing an unbound room identity.
- Additional confirmed fact: The governed HTTP transport caps every NDJSON line at 32 KiB, while one valid parallel `FRAME_SEALED` line may carry a canonical Frame result of up to 262,144 characters plus JSON framing. Without a parallel-specific transport limit, a valid sealed Frame is rejected before Java can persist it; raising the legacy client limit would unnecessarily widen the existing V3 boundary.
- Verification evidence update: The signed RoomGraphCommand contract now reserves `room_id` and a provider budget of three through six exclusively for the exact parallel Intake agent profile, while the canonical materializer takes the room identity from the locked room-epoch authority and fixes the aggregate budget at six. Legacy commands sharing the same target output schema remain on V3. Cross-language contract, context, selector, assembly, finalization, and adjacent regression checks passed: Python 63 tests and Java 66 tests, zero failures.
- Verification evidence update: The Python-private exact-three NDJSON stream now publishes a self-hashed pre-provider Frame authority header and per-event timestamps; the Java consumer validates that authority before admission, assigns every interleaved public V4 event through the transactional staging owner, persists each seal immediately, and returns only after re-reading three current SEALED slots. The parallel response line bound is 1 MiB while every existing legacy caller remains pinned to 32 KiB. Independent focused verification passed: Python 79 tests and Java 23 tests, zero failures; the two unrelated Hearing working-tree changes were byte-identical before and after verification.
- Verification evidence update: The Target AGENT role now exposes one profile-selecting `AgentRunExecutionGateway`, retains the legacy and parallel delegates as non-beans, and owns the `TargetIntakeCommandMaterialStore` required by the frozen parallel context resolver. The target source-set compiled all 28 role-specific sources and seven focused wiring/gateway tests passed with zero failures; an independent read-only rerun reproduced both results and confirmed the two unrelated Hearing working-tree files remained byte-identical.
- Additional confirmed fact: The current one-way Python-to-Java NDJSON exchange starts the three Provider lanes after Python has emitted the three pre-provider Frame headers, while Java performs durable frame-set admission only after receiving those headers. No Java-issued admission receipt is returned to Python before Provider execution, so a Java admission rejection cannot prove that Provider work was never started.
- Additional confirmed fact: The current target command credential binds only the immutable command envelope. It does not bind a parallel PREPARE/EXECUTE phase or a Java admission-receipt hash, and V081 has no independent durable stream-session receipt row. A self-hashed request header alone therefore cannot serve as execution authority for a second-stage Provider call.
- Additional confirmed fact: The first admission-receipt decoder attempted to instantiate the `typing.Literal` alias used for `ParallelFrameType`. Every otherwise valid Java EXECUTE receipt therefore failed before its signed receipt hash could be checked and the endpoint returned `TARGET_E2E_PARALLEL_DELIVERY_BINDING_REJECTED`; the PREPARE half of the same handshake remained successful.
- Additional confirmed fact: A partial V4 prefix has no request-bound resume authority. Java does not send the current per-slot generation, frame identity, next local index, projection hash, or slot state when re-entering a RUNNING attempt, and the Python production bundle fixes all three lanes at generation 1. A transport failure after one durable item or one sealed lane therefore cannot distinguish an exact sealed replay from an ambiguous started lane without re-invoking work.
- Additional confirmed fact: The persisted partial-resume path has three independent replay defects beyond the missing receipt watermarks: initial admission replay counts every stored Frame generation instead of only the three generation-1 manifests, so a legitimate generation-2 replacement makes the original admission appear incomplete; the Java stream-session identity is derived only from the initial preparation authority, so a later plan restarts transport sequence zero inside the same V081 session identity; and the client EOF gate requires all three seals to occur in the current HTTP response instead of accepting already SEALED durable slots. Each defect rejects a valid lane-local recovery even when V081 already contains the exact current authority.
- Additional confirmed fact: The first durable execution-plan implementation left the mixed-subset Python attempt without a completion or lease-release transition, rejected post-model same-attempt recovery before the selected lane could execute, and encoded a planner-created generation-2 replacement with a different validation path than the live reset path. A concurrent or replayed single-lane retry could therefore remain `EXECUTING` indefinitely or reject the same replacement authority as drift; an already sealed exact-three replay was also evaluated by the new-work deadline/status gate before its durable result state was classified.
- Additional confirmed fact: V4 source events are now hash-bound and replayable by the Java SSE service with `v4:<attempt>:<sequence>` cursors, but the frontend protocol allowlist and accumulator still reject `agent-stream.v4`, and intermediate V4 staging transactions do not publish an after-commit wakeup. The browser therefore cannot yet consume the three lanes in real time even though PostgreSQL contains their durable events.
- Additional confirmed fact: The Dossier Frame provider schema permits independently authored `projection_path_id` and `candidate_value`, while `fact_key` and `source_binding_id` are optional. Python copies those fields into the public projection and the Java exact-three assembler checks only projection-slot identity and order, not equality with the sealed `dossier_patch`. A valid-looking V4 preview can therefore display a claim, position, or response that differs from the proposal later assembled for formal commit.
- Additional confirmed fact: The first frontend V4 consumer used one cross-protocol event allowlist. A V4 envelope carrying a legacy `visible_delta` event could pass normalization and enter the legacy reducer without frame-set, registry, generation, local-index, or delivery-class checks; V4 `final` and `error` also reached the shared terminal branch without proving `DURABLE_TERMINAL`.
- Additional confirmed fact: The command-based parallel model-context builder references `execution.fence.room_fencing_token` even though its boundary receives only the sealed command, thread and immutable input pair. Both direct context construction and production-bundle construction therefore fail with `NameError` during PREPARE, before any Provider call; the already verified Target invocation carries the missing room fencing token but the preparation service does not propagate it.
- Additional confirmed fact: The narrowed Dossier projection registry still authorizes only a root name and projection kind; every nested path segment and the projected value remain independently provider-authored. A schema-valid Frame can therefore introduce an unregistered semantic leaf even though its frame/context hashes are valid.
- Additional confirmed fact: The Dossier projection registry also still exposes `claim_resolution` and `respondent_attitude` while the same sealed Frame carries an independent typed `matrix_patch`. The assembler does not reconcile those representations, so a schema-valid Dossier item can contradict or bypass the matrix claim/response authority in the same proposal.
- Additional confirmed fact: The first exact Dossier projection repair targeted `case_story.current_facts`, but the existing Intake case-story contract persists `one_sentence_summary` and does not define `current_facts`. The focused frame tests therefore passed while the assembled formal dossier patch no longer reused the current persisted structure and could be rejected or retain an unrecognized case-story member at the formal merge boundary.
- Additional confirmed fact: Dossier `public_projection_items` are emitted to the Java V4 ledger and browser after validating only the standalone projection-item shape, while their equality with the typed `matrix_patch` is checked only when the complete Dossier Frame closes. The provider and runtime still permit up to 32 such items, so a shape-valid item can become durable preview state before any typed matrix row authorizes its factual value; a later Frame rejection/reset cannot prevent that provisional fact from already having been displayed.
- Additional confirmed fact: The Dossier summary string is preserved byte-for-byte by the Python Frame contract, but the Java assembler validates it with UTF-16 `String.length()` and returns `strip()` while the frontend also uses UTF-16 `.length`. Leading/trailing whitespace can therefore make the streamed Python value differ from the formally assembled Java patch, and supplementary Unicode characters can produce different 20,000-character boundary decisions across Python, Java and JavaScript.
- Verified working-tree status: each Dossier preview item now carries a complete typed current-source row and is rejected before any projection event when the row scope, stance or candidate text is invalid; complete Frame validation and the Java assembler both require the ordered item rows to equal the typed matrix rows. Python, Java and JavaScript preserve the accepted string and use Unicode code-point counts for the cumulative 20,000-character boundary. Focused verification passed 40 Python tests, 13 Java tests and 139 frontend tests; the frontend production build completed, `git diff --check` passed, and independent read-only semantic review reported no remaining P0/P1 blocker. Integrated activation/UAT has not yet run for this working-tree state.
- Additional confirmed fact: The first receipt-cycle recovery draft rewrote the global Graph attempt-update trigger so an immutable receipt-cycle row could authorize an `EXECUTING` attempt owner/fence handoff, but the cycle row was not database-bound to the exact parallel Intake profile. The runtime role can insert cycle rows and update attempts, so the database boundary also widened the legacy V3 attempt authority.
- Additional confirmed fact: The first same-attempt receipt handoff accepted only a fresh lease `TAKEOVER`. If the takeover/rebind transaction committed but its response was lost, an immediate retry observed `IDEMPOTENT` and was rejected; after expiry a later takeover advanced beyond the attempt fence and could no longer adopt the committed handoff. The corresponding receipt-cycle completion path also lacked an explicit unknown-commit adoption branch.
- Additional confirmed fact: Receipt handoff initially proved only a different receipt hash over the same frame-set authority. It did not bind the new receipt to the latest cycle, exact prior provider-call count, sealed sibling results, failed-lane replacement generation or slot-version monotonicity, so an older still-valid receipt could race a newer cycle. Initial cycle replay also returned normal EOF after the first execution had ended with a retryable batch error, producing a different transport terminal outcome for identical receipt bytes.
- Additional confirmed fact: Receipt completion/recovery acquired `lease -> command -> attempt`, while parallel technical finalization acquired `command -> attempt -> lease`; concurrent replay and finalization therefore had an inverted-lock deadlock window. The retryable batch classifier also did not prove that completed and failed lane maps formed an exact, disjoint partition matching the canonical SEAL/INTERRUPT terminal events.
- Additional confirmed fact: The first immutable receipt execution/cycle schema referenced the mutable current attempt by `(attempt_id, thread_id, command_id, fencing_token)`. Advancing the same attempt from fence N to N+1 would therefore invalidate every retained fence-N receipt row at the foreign-key boundary, so the intended append-only recovery history could not survive its first legitimate handoff.
- Verification evidence update: Focused V4 cursor and replay checks passed independently: cursor 3/3 and service 4/4, including contiguous replay from sequence 0, formal-commit gating of FINAL, and the adjacent legacy V3-backed stream path. The two unrelated Hearing working-tree files remained byte-identical.
- Verification evidence update: The parallel command transport now completes a signed PREPARE exchange without Provider execution, persists Java frame-set admission, and issues a fresh EXECUTE credential bound to the canonical admission-receipt hash before the private Frame stream starts. Independent focused verification passed 36 Python checks and seven Java checks, including phase/hash mismatch, receipt tamper, duplicate-member, lane-order, and valid Literal lane decoding; the two unrelated Hearing working-tree files remained byte-identical.
- Verification evidence update: Parallel recovery now retains immutable receipt execution authority per receipt and fencing token, permits same-receipt cross-owner continuation only before any Provider call or completed receipt cycle, binds successor receipts to the exact prior cycle and Provider-call count, and adopts an exact durable technical completion when the commit response is lost. Independent focused verification passed 154 Python checks and three Java client checks with zero failures; a separate read-only authority and lock-order audit found no blocker.
- Additional confirmed fact: The Java exact-three assembler validates the Quality gap shape and then passes `quality.gaps()` directly into `nextState`, while the separately parsed Dossier typed matrix is never supplied to that state derivation. A Quality gap whose `linked_fact_keys` is absent from the Dossier matrix, or whose linked current-source row was already substantively supplied in the same turn, can therefore become a formal `missing_information` blocker without cross-Frame authority reconciliation. This contradicts the frozen plan's required merge order and makes the formal next-state result depend on an unchecked model gap binding.
- Impact update: A three-Frame run can formally persist a stale or unbound blocking gap even though its Dossier lane proves the current answer supplied that fact, preventing a qualifying Intake actor from advancing and making the new parallel path ineligible for activation until the exact-three merge invariant is restored.
- Verification evidence update: The exact-three assembler now reconciles non-empty Quality gap fact bindings against the sealed Dossier matrix before deriving next state: unknown fact keys fail closed, bindings wholly covered by substantive current-source rows are removed without changing any score, and prior-only `NOT_ADDRESSED` bindings remain blocking. `IntakeParallelFrameAssemblerTest` passed 14/14, including all six arrival orders and adjacent phase/action and Dossier authority regressions.
- Impact: Activating the parallel profile now would either be rejected before execution or bypass the unique durable FINAL/RESULT_READY and frozen-context authorities; the three-frame refactor is not eligible for integrated UAT until these runtime boundaries are connected.
- Identifying metadata: observed 2026-08-25; execution profile `PARALLEL_FRAMES_V1`; agent profile `dispute-intake-officer.parallel-frames.v1`; technical protocols/artifacts `agent-stream.v4`, `intake-turn-proposal.v2`, `room-graph-result.v1`.

## P0-20260825-V081-LEGACY-GENERATION-RESET-CONSTRAINT

- Severity: P0
- Status: FIXED_ACTIVATION_MIGRATION_VERIFIED
- Component: V081 Intake parallel-frame staging migration compatibility
- Confirmed fact: The fresh activation migration preflight rolled back V081 and exited before activation provisioning because PostgreSQL rejected the new `ck_agent_run_stream_event_type_v4` constraint with SQLSTATE `23514`.
- Root cause and evidence: The existing schema at version 080 contains eleven `agent-stream.v3` rows whose persisted `event_type` is `generation_reset`. V081 drops the V3 event-type constraint and replaces it with a V4 superset that includes `frame_generation_reset` but omits the still-valid legacy `generation_reset`, so adding the replacement constraint rejects historical rows.
- Impact: No candidate activation can pass Flyway preflight or start against an existing valid V080 database, so the parallel Intake refactor cannot enter integrated UAT even though the application slices pass focused verification.
- Identifying metadata: observed 2026-08-25 12:07 CST; database schema version `080`; failing migration `V081__intake_parallel_frame_staging.sql` line 12; constraint `ck_agent_run_stream_event_type_v4`; launcher outcome `SOURCE_TOPOLOGY_STOPPED_BEFORE_IRREVERSIBLE_PROVISION`.

## P0-20260825-PARALLEL-ASSEMBLY-STORE-FINAL-PROXY

- Severity: P0
- Status: FIXED_ACTIVATION_VERIFIED / UAT_PENDING
- Component: Java Intake parallel assembly persistence bean activation
- Confirmed fact: After V081 through V084 migrated successfully, the fresh activation preflight still exited before readiness while creating the `jdbcIntakeParallelAssemblyStore` bean.
- Root cause and evidence: `JdbcIntakeParallelAssemblyStore` is a final concrete Spring bean with transactional advice. Spring selected a CGLIB class proxy during application-context startup, and CGLIB rejected the bean with `Cannot subclass final class com.example.dispute.workflow.infrastructure.persistence.intake.parallel.JdbcIntakeParallelAssemblyStore`.
- Additional confirmed fact: After making the assembly store proxyable, the next application-context preflight failed on the same mechanism in final transactional bean `JdbcIntakeParallelFrameAdmissionAuthorityResolver`; the first focused fix therefore repaired only the first bean encountered by eager initialization and did not cover the complete affected component set.
- Impact: The migrated candidate schema cannot start the Java application context, so no API, worker, Python, frontend, or integrated UAT stage can be activated.
- Identifying metadata: observed 2026-08-25 12:11 CST; latest migrated version observed `084`; bean `jdbcIntakeParallelAssemblyStore`; exception `AopConfigException` caused by `IllegalArgumentException: Cannot subclass final class`.

## P0-20260825-PARALLEL-RECEIPT-PROVISIONING-TRUNCATE-CONFLICT

- Severity: P0
- Status: FIXED_ACTIVATION_VERIFIED / UAT_PENDING
- Component: Local target activation Graph-state provisioning
- Confirmed fact: A subsequent fresh activation stopped the owned source topology, then failed before activation issuance while `provision-local-target.py` reset the isolated Graph candidate state.
- Root cause and evidence: The provisioner executes `TRUNCATE TABLE ... CASCADE` across candidate Graph runtime tables. The newly introduced parallel receipt authority tables install a mutation-rejection trigger that also rejects `TRUNCATE`, raising `parallel receipt authority rows are immutable`; the environment-reset authority and the runtime append-only authority therefore have no explicit separation.
- Impact: Once parallel receipt rows exist, no later local target activation can be provisioned, and the launcher leaves the owned application topology stopped after its fail-closed rollback boundary, blocking UAT and future candidate rotations.
- Identifying metadata: observed 2026-08-25 after activation `p9act.v1.6508acd2ce6cfe67b29e6edf0ba3f897`; candidate HEAD `f0244861`; failing function `reset_local_graph_candidate_state`; PostgreSQL trigger function `graph_runtime.reject_agent_graph_parallel_receipt_mutation()`.

## P0-20260825-PARALLEL-ROOM-MESSAGE-INTERNAL-ERROR

- Severity: P0
- Status: FIXED_FOCUSED_VERIFIED / ACTIVATION_UAT_PENDING
- Component: Target E2E Intake parallel ROOM_MESSAGE admission
- Confirmed fact: On healthy activation `p9act.v1.f752ba169b0f1eb32803313a016418c9`, the first authenticated USER ROOM_MESSAGE after a successfully committed V3 opening in fresh case `CASE_P9_6A8D1C10_1` returned HTTP 500 `INTERNAL_ERROR` before the UAT harness received an AgentRun identifier.
- Root cause and evidence: `CanonicalTargetIntakeMaterializer` correctly issued the authenticated ROOM_MESSAGE as `agent-stream.v4`, but the latest persisted `enforce_target_e2e_intake_command_material()` trigger still required `run.protocol='agent-stream.v3'`. PostgreSQL therefore raised SQLSTATE `23514` with `target E2E Intake material does not bind its durable AgentRun attempt` while inserting `target_e2e_intake_command_material`; the caller-owned transaction rolled back the draft message, run, attempt, admission, and material together. The immediately preceding opening run `target-intake-run:56b642d77d0b3f2a808814da8405851b` remains `COMPLETED / COMMITTED`, the case remains `INTAKE_PENDING`, neither party has an Intake completion row, and all Evidence cardinalities remain zero.
- Impact: The newly activated three-frame Intake path cannot accept its first substantive party turn, blocking verification of V4 frame streaming, assembly, formal commit, and every downstream UAT stage.
- Identifying metadata: observed 2026-08-25 12:46 CST; case `CASE_P9_6A8D1C10_1`; actor `user-local/USER`; request `REQ_1d206dca31214c618133284caaa120f2`; trace `TRACE_494715dc716cf32c190b5e3d95cd41e8`; HTTP code `500`; public code `INTERNAL_ERROR`.

## P0-20260825-PARALLEL-V4-NEGATIVE-BASELINE-PROGRESS

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED
- Component: Target E2E Intake V4 AgentRun activity heartbeat boundary
- Confirmed fact: On fresh activation `p9act.v1.a28c58896637dfaf59b801e11090d4f4`, the first substantive USER ROOM_MESSAGE in fresh case `CASE_P9_6A8D210C_1` was admitted as V4 run `target-intake-run:d3287bf357bf3281a51c323560720a99`, but its Temporal execution failed before any parallel frame-set or Provider call was created.
- Root cause and evidence: The V4 attempt is correctly persisted with `last_sequence_no=-1` so its first public frame can own global sequence `0`. `ExecuteAgentRunActivityImpl` constructs `AgentRunHeartbeatMonitor` before execution, and that monitor constructs `AgentRunProgress` from the persisted attempt. `AgentRunProgress` still enforces the legacy V3-only invariant `lastSequenceNo >= 0` and throws `IllegalArgumentException: lastSequenceNo must not be negative`; the Temporal Activity consequently terminates before the V4 execution gateway can admit or stream any of the three lanes.
- Impact: Every correctly initialized V4 Intake attempt is rejected at the activity heartbeat boundary before model execution, so the three-lane parallel path cannot produce preview frames, assemble a proposal, formally commit the turn, or continue downstream UAT.
- Identifying metadata: observed 2026-08-25 13:03 CST; case `CASE_P9_6A8D210C_1`; actor `user-local/USER`; command `intake-message:d3287bf357bf3281a51c323560720a99`; logical run `target-intake-run:d3287bf357bf3281a51c323560720a99`; attempt `target-intake-attempt:d3287bf357bf3281a51c323560720a99:1`; Temporal workflow run `1a614460-cb90-4a51-8862-8f14b6f12a36`; focused and independent verification both passed `ExecuteAgentRunActivityHeartbeatTest` 5/5 on 2026-08-25.

## P0-20260825-DEMO-PURGE-TARGET-FINALIZATION-FK-DRIFT

- Severity: P0
- Status: PARTIALLY_FIXED / JAVA_STORE_CLEANED / GRAPH_STORE_RESIDUAL_CONFIRMED
- Component: Reviewer-authorized failed UAT case purge
- Confirmed fact: The reviewer-authorized purge now deletes the Java/domain closure and returns HTTP 200, but the same request leaves the separately persisted Graph thread, commands, attempts, result, lease, permits and checkpoint closure intact. For `CASE_P9_6A8E195B_1`, the Java/domain cardinalities became zero while the Graph cardinalities remained unchanged after the successful request.
- Root cause and evidence: The pre-V086 function deleted `agent_execution_manifest` before post-V040 Target E2E finalization receipts, violating `target_e2e_finalization_receipt.fk_target_e2e_finalization_manifest`. V086 corrected the dependency and immutable-trigger graph, and V087 corrected the event-slot resolver. The current application boundary still injects only `FulfillmentCaseRepository` and the Java `DemoCasePurgeStore`; `DemoCasePurgeService.purge` invokes only `purgeStore.purge`, and `JdbcDemoCasePurgeStore` invokes only the Java-database `purge_simulated_dispute_case` function. No Graph-store purge participant is invoked before the API reports `deleted=true`.
- Impact: Failed or obsolete simulated UAT cases can be reported as deleted while their Graph execution and checkpoint authorities remain live in the shared environment. The returned deletion acknowledgement therefore does not prove an exact cross-store closure and cannot safely serve as the cleanup gate before a fresh activation or replacement UAT.
- Identifying metadata: observed initially 2026-08-25 13:08 CST; requested cases `CASE_P9_6A8D1C10_1` and `CASE_P9_6A8D210C_1`; initial failing request `REQ_PURGE_CASE_P9_6A8D1C10_1`, trace `TRACE_622911e067e75edcfbb2a83f50e960e0`, constraint `fk_target_e2e_finalization_manifest`; V086 isolated and independent tests passed 4/4; first deployed retry used request `REQ_PURGE_FAILED_UAT_20260825`, trace `TRACE_397cf7ef07f86814af9e353283844bce`, and failed in `demo_case_purge_row_case_id`; V087 independent verification passed 4/4 through all 96 migrations; the two original failed UAT cases were physically deleted with audit IDs `PURGE_4B2B43DC371466BBE5227304947D79EA` and `PURGE_4B04615194506A781633DC7194275AC3`. Cross-store residual reproduced 2026-08-26 for `CASE_P9_6A8E195B_1`, Graph thread `grt.v1.01a03b1c6f3c760abd023880bd57f576`, request `REQ_eb519446dfa041bab3cbb4f4620dace5`, audit `PURGE_0466702D2C401537481942C976997B87`: Java/domain rows zero; Graph commands/attempts `2/2`, result `1`, lease `1`, execution/cycle receipts `2/1`, permits `2`, checkpoints/writes/blobs `27/126/61` remained.
- Recurrence metadata: observed 2026-08-26 for `CASE_P9_6A8E7201_1`, request `REQ_eae7e97f305c4cba9dba0a7989da7eac`, Java audit `PURGE_4D5B084784858561F8F79A6EACB61665`, and Graph thread `grt.v1.01a03c74cdcb7e9087a49a9855a40a9c`. The reviewer endpoint returned HTTP 200 / `deleted=true`, the case returned HTTP 404, and Java business rows became zero, while Graph retained commands/attempts `2/2`, result `1`, lease `1`, permit `1`, invocation nonces `3`, receipt executions `1`, and checkpoints/writes/blobs `19/84/44`; the current V4 command and attempt remained `EXECUTING` with provider call count `4`.

## P1-20260825-INTAKE-EPOCH-EXECUTION-PROFILE-UNPINNED

- Severity: P1
- Status: RESOLVED / DEPLOYED / UAT_VERIFIED
- Component: Target Intake ROOM_MESSAGE execution-profile authority
- Confirmed fact: Authenticated USER/MERCHANT `ROOM_MESSAGE` requests are currently assigned the parallel V4 profile solely from their source type at materialization time, while opening requests remain V3. The persisted active Intake room epoch and its immutable Target activation binding do not record whether that epoch was issued for `MONOLITHIC_V3` or `PARALLEL_FRAMES_V1` ROOM_MESSAGE execution.
- Root cause and evidence: `CanonicalTargetIntakeMaterializer.isParallelRoomMessage` treats every case-party ROOM_MESSAGE as parallel, and materialization then replaces the activation-wide agent profile with `dispute-intake-officer.parallel-frames.v1`. Neither `case_room_epoch` nor `target_e2e_room_epoch_binding` currently carries an epoch-scoped Intake execution-profile discriminator, so replay validates only the behavior selected by the currently deployed code rather than the profile frozen when the epoch was created.
- Impact: Deploying or rolling back the parallel implementation can change the protocol and command shape of a still-active historical Intake epoch. An old epoch may silently switch from V3 to V4, while a future rollback can no longer reproduce an already-issued V4 authority; safe gradual activation and exact command replay are therefore not guaranteed.
- Identifying metadata: confirmed by committed-source audit on 2026-08-25 at candidate `ec5b5d05`; affected profile `dispute-intake-officer.parallel-frames.v1`; affected execution profile `PARALLEL_FRAMES_V1`; adjacent opening protocol `agent-stream.v3`.

## P0-20260825-PARALLEL-V4-EMPTY-STREAM-FAILURE-RESULT-REJECTED

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING
- Component: Target Intake V4 AgentRun terminal failure reconciliation
- Confirmed fact: In fresh case `CASE_P9_6A8D520B_1`, the first authenticated USER parallel ROOM_MESSAGE was submitted exactly once and its V4 run exhausted three Temporal Activity attempts without any public Frame event or FINAL. When the Activity closed the failure, `ExecuteAgentRunResult` rejected the persisted empty-stream baseline `lastSequenceNo=-1` with `IllegalArgumentException: attemptNo and lastSequenceNo are invalid`; Temporal then ended the workflow as `NON_RETRYABLE_FAILURE` while the durable AgentRun and frontend projection remained `RUNNING/PENDING`.
- Root cause and evidence: V4 intentionally starts an empty attempt at sequence `-1`, as already accepted by `AgentRunAttemptHeartbeat`, but `ExecuteAgentRunResult` still enforces the legacy `lastSequenceNo >= 0` rule for every outcome. `ExecuteAgentRunActivityImpl.failedResult` forwards the heartbeat/gateway sequence unchanged, so a failure before the first V4 event cannot be represented and never reaches the durable terminal-failure ledger boundary. Source inspection also confirms that the current `JpaAgentRunLedger.recordAttemptFailureResult` appends only a V3 terminal error, so simply relaxing the result constructor would still leave V4 terminal ownership incomplete.
- Impact: Any parallel Intake infrastructure or admission failure before the first public Frame can leave Temporal and the durable/UI state split, preventing authoritative same-command recovery and making a safe UAT resume impossible without a terminal reconciliation repair.
- Identifying metadata: observed 2026-08-25; case `CASE_P9_6A8D520B_1`; message `MESSAGE_210e65f7b09d40c8bf65f18d4e89acfa`; run `target-intake-run:0c92a7f2a1e5308eabf7f8232ad7f649`; attempt `target-intake-attempt:0c92a7f2a1e5308eabf7f8232ad7f649:1`; command `intake-message:0c92a7f2a1e5308eabf7f8232ad7f649`; failing constructor `ExecuteAgentRunResult.java:40-41`.

## P0-20260825-PARALLEL-V4-TERMINAL-PERSISTENCE-SQLTYPE-REJECTED

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING / UAT_BLOCKED
- Component: Target Intake parallel V4 AgentRun terminal persistence
- Confirmed fact: In fresh USER-started case `CASE_P9_6A8D6C59_1`, the first authenticated parallel `ROOM_MESSAGE` was submitted exactly once. The Temporal Activity exhausted three execution attempts before any of the three public Frame streams started, and Java terminal persistence failed each time with PostgreSQL SQLSTATE `07006`.
- Root cause and evidence: `PostgresAgentRunV4EventWriter` passed `EventWriteCommand.occurredAt` to `MapSqlParameterSource` as a raw `java.time.Instant`. PostgreSQL JDBC cannot infer a SQL type for that object and rejects the source-event insert at `PostgresAgentRunV4EventWriter.java:136` with `SQLSTATE 07006: Can't infer the SQL type to use for an instance of java.time.Instant`. The same writer owns ERROR terminal persistence, so the failure event itself also could not be committed. No DIALOGUE, DOSSIER, or QUALITY public Frame event preceded the rejection, and no provider generation authority was created.
- Impact: The parallel Intake turn cannot emit a first packet, seal any lane, assemble the exact-three result, or formally commit, so latency measurement and downstream UAT are blocked. Retrying the same UI message would risk duplicating the already admitted turn and is therefore prohibited.
- Identifying metadata: observed 2026-08-25 on candidate `0d83fd1882f41efa0e5158cafa53f9346258a419`, activation `p9act.v1.d1b81554afe79427fa59a69ee22a11d6`, case `CASE_P9_6A8D6C59_1`; USER message `MESSAGE_6ee14ebc9d7648f09a47c0ba4c383abc`; run `target-intake-run:80b16ad173603307b352aea63f2c4009`; attempt `target-intake-attempt:80b16ad173603307b352aea63f2c4009:1`; command `intake-message:80b16ad173603307b352aea63f2c4009`.

## P0-20260825-PARALLEL-V4-PREFRAME-TERMINAL-ERROR

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING
- Component: Target Intake parallel V4 ROOM_MESSAGE execution
- Confirmed fact: On the healthy fresh activation bound to candidate `7e04c8eed542d3f192b9545a3f869bf68652e945`, the only authenticated USER ROOM_MESSAGE in fresh case `CASE_P9_6A8D726F_1` was persisted exactly once, but its V4 stream produced `ERROR` as sequence `0` and produced no DIALOGUE, DOSSIER, or QUALITY Frame start, preview, or seal event.
- Root cause and evidence: The production endpoint injects `_RuntimeTargetE2EVerifier`, but that lifecycle proxy implemented only the monolithic execution and reconciliation verifier methods and omitted `verify_parallel_envelope`. All three PREPARE requests therefore failed at `app.api.graph_commands:stream_target_e2e_command:506` with `AttributeError` before frame-set admission or Provider execution, even though the underlying `TargetE2EInvocationVerifier` already implements the required phase/receipt-bound verification. The Activity retained only `io.temporal.failure.ApplicationFailure: agent run infrastructure failure; type=AgentRunRetryableFailure` because `ExecuteAgentRunActivityImpl.retryFailure` did not attach the original cause; the Python error log supplied the decisive inner class and call site.
- Verification evidence: The focused lifecycle proxy regression covers both PREPARE without an admission receipt and EXECUTE with the bound receipt hash; both cases pass and preserve the exact verified invocation object. `git diff --check` is clean.
- UAT evidence: Fresh activation `p9act.v1.91be7f340eb00262b50bb152464efef9` passed the target-E2E envelope verifier and reached the parallel service PREPARE dispatch, proving the repaired lifecycle verifier boundary is active. The next failure was a distinct runtime-service proxy defect.
- Impact: The new Intake path cannot expose a model first packet, assemble the exact-three proposal, formally persist the Intake turn, freeze the case matrix, or proceed to downstream UAT. Re-submitting the already persisted USER message is prohibited.
- Identifying metadata: observed 2026-08-25; activation `p9act.v1.92caec9fe9084ae95fbf1d9a72819c13`; candidate `7e04c8eed542d3f192b9545a3f869bf68652e945`; case `CASE_P9_6A8D726F_1`; message `MESSAGE_22a2329206fa459ab41db7bb8d6bd6d5`; command `intake-message:377a9a1e99db3c76a305d081950dc2bc`; run `target-intake-run:377a9a1e99db3c76a305d081950dc2bc`; attempt `target-intake-attempt:377a9a1e99db3c76a305d081950dc2bc:1`; single-sample UAT stopped after the first durable V4 ERROR.

## P0-20260825-PARALLEL-V4-PREPARE-PROXY-MISSING

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING
- Component: Target Intake parallel V4 PREPARE runtime delegation
- Confirmed fact: On the fresh activation containing the parallel envelope-verifier repair, the first authenticated USER ROOM_MESSAGE again produced only V4 `ERROR` sequence `0`; DIALOGUE, DOSSIER, and QUALITY each produced zero starts, deltas, seals, checkpoints, or results, and no Provider call occurred.
- Root cause and evidence: `stream_target_e2e_command` invokes `parallel_service.prepare(...)` for the PREPARE phase at `app.api.graph_commands:566`, but the production-injected `_RuntimeParallelIntakeStreamService` implements only a stale `open_stream(...)` proxy. All three Temporal Activity attempts therefore reached Python and failed with `AttributeError` at that exact call site before frame-set admission. The same proxy also omits the required `admission_receipt` argument declared by `ParallelIntakeFrameStreamService` and supplied by the EXECUTE endpoint, confirming that its runtime boundary does not implement the current two-phase protocol. The run remained `FAILED / UNCOMMITTED`, with no proposal, `RoomGraphResult`, artifact, FINAL, RESULT_READY, matrix write, dossier update, Agent reply, or formal Intake commit.
- Verification evidence: The focused two-phase proxy regression proves exact PREPARE forwarding and exact EXECUTE forwarding including the admission receipt; the test passes and `git diff --check` is clean. Missing runtime or missing parallel service remains fail closed.
- UAT evidence: Fresh activation `p9act.v1.0acd30a32ee24f39586829aebe9722ec` reached the concrete parallel service and returned a domain HTTP 409 instead of raising the missing-method `AttributeError`, proving that both runtime proxy methods were deployed. The 409 is tracked as a distinct downstream blocker.
- Impact: The parallel Intake execution path cannot create any of its three lanes, expose a substantive first packet, assemble the existing output contracts, or advance the case. The already-persisted USER message cannot be safely resubmitted.
- Identifying metadata: observed 2026-08-25; activation `p9act.v1.91be7f340eb00262b50bb152464efef9`; candidate `5d705fd5a136a5d20ec409039242e97e6f2ccf0e`; case `CASE_P9_6A8D8C6A_1`; message `MESSAGE_0c52305f92784ee5b8d9daf44e06424d`; command `intake-message:ab85e66c5d4a3bb6933d10393a7191e3`; run `target-intake-run:ab85e66c5d4a3bb6933d10393a7191e3`; attempt `target-intake-attempt:ab85e66c5d4a3bb6933d10393a7191e3:1`.

## P0-20260825-PARALLEL-V4-PREPARE-CONFLICT-CAUSE-LOST

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING
- Component: Target Intake parallel V4 PREPARE admission and Java Activity error propagation
- Confirmed fact: After both lifecycle proxies were deployed, the first USER ROOM_MESSAGE invoked the Python target-E2E stream endpoint three times and received HTTP 409 on every Activity attempt. The run still produced only V4 `ERROR` sequence `0`; no frame set, lane event, Provider call, proposal, result, matrix write, dossier update, Agent reply, or formal commit was created.
- Root cause and evidence: The signed V4 command reused the thread's immutable INITIAL_FORM domain snapshot, whose `current_dossier` is `{}` and contains no `party_intake_state`, even though Java separately froze the latest persisted dossier in `parallelTurnContext`. Python PREPARE is bound to the command's signed snapshot/event authority and does not read that Java-private context, so all three identical attempts passed snapshot/event authority checks and then failed while validating `PartyIntakeState` from `None`. Python returned non-retryable HTTP 409 `GRAPH_CONTRACT_REJECTED` with `GraphContractError("parallel Intake party state is invalid")` before Provider or frame admission. Java's PREPARE response session discarded that structured body and rethrew a generic infrastructure failure; the later `TARGET_INTAKE_TERMINAL_NO_COMMIT_RUN_INVALID` is a consequence of the already failed run, not the first error.
- Impact: The new Intake path remains unable to start any of its three nodes, measure a substantive first packet, assemble the existing output schemas, or progress the case. The already-persisted USER turn has no proven safe user-level replay entry.
- Identifying metadata: observed 2026-08-25; activation `p9act.v1.0acd30a32ee24f39586829aebe9722ec`; candidate `3b0b96dd02878b5307bf24d8052eb6e550ab90d5`; case `CASE_P9_6A8D92F2_1`; room `ROOM_89a68620b9b4471890e61aea2c300caa`; message `MESSAGE_6ad3f68ae6844e7ca0a6889591ac986c`; command `intake-message:4614901179393bf886e5ede42c0ee0f3`; run `target-intake-run:4614901179393bf886e5ede42c0ee0f3`; attempt `target-intake-attempt:4614901179393bf886e5ede42c0ee0f3:1`; focused verification on 2026-08-25: `IntakeSnapshotAndEventPublisherTest` 7/7, `CanonicalTargetIntakeMaterializerTest` 18/18, `HttpTargetE2EIntakeParallelFrameExecutionClientTest` 4/4.

## P0-20260825-PARALLEL-V4-FORMAL-COMMIT-PROTOCOL-PINNED-V3

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / DEPLOY_PENDING
- Component: Intake formal commit AgentRun authority
- Confirmed fact: The parallel ROOM_MESSAGE materializer and AgentRun ledger issue the exact V4 profile with protocol `agent-stream.v4`, but the formal Intake commit authority query cannot select any such run even after a valid FINAL and RESULT_READY transition.
- Root cause and evidence: `JdbcIntakeFormalCommitPort` locks and validates the authoritative AgentRun/attempt using an otherwise exact identity query, but that query hard-codes `run.protocol = 'agent-stream.v3'`. The predicate is independent of the signed command's explicit parallel discriminator and therefore deterministically excludes every legitimate V4 run before domain writes.
- Impact: A parallel turn can complete all three frames and technical assembly yet can never atomically commit its dossier, matrix, Agent reply, manifest, receipt, or command completion.
- Identifying metadata: observed 2026-08-25 during pre-deployment reverse review; candidate worktree based on `3b0b96dd02878b5307bf24d8052eb6e550ab90d5`; source boundary `JdbcIntakeFormalCommitPort.java:684-700`; focused verification on 2026-08-25: `JdbcIntakeFormalCommitPortProtocolTest` 1/1; no Provider or runtime mutation was used to confirm the defect.

## BUG-20260825-JDBC-INTAKE-FORMAL-FIXTURE-BYPASSES-BINDING-TRANSACTION

- Severity: P2 (test infrastructure)
- Status: FIXED / FOCUSED_VERIFIED
- Component: `JdbcIntakeFormalCommitPortTest` PostgreSQL fixture
- Confirmed fact: The isolated formal-commit integration node migrated a clean PostgreSQL database through V089, but fixture setup failed while binding its Intake event before it could execute the formal commit/replay assertion.
- Root cause and evidence: The fixture directly constructs `JdbcIntakeGraphBindingStore`, so its method-level Spring `@Transactional` annotations are not proxied. Registration, initial snapshot, event history, and current event-slot authority therefore run as separate auto-commit statements. V080's deferred history constraint is evaluated when the event-history insert commits and correctly rejects it because the matching current slot authority has not yet been written.
- Impact: The integration test cannot exercise formal commit or prove V3 adjacency even though production uses the proxied transactional store; this is a false-negative test boundary, not evidence of a production event-binding failure.
- Identifying metadata: observed 2026-08-25 in isolated PostgreSQL 16.14; Flyway applied 98 migrations through V089; first error `require_intake_event_history_current_authority()` from `JdbcIntakeFormalCommitPortTest.insertFixture -> JdbcIntakeGraphBindingStore.bindEvent`; after the fixture used one real transaction, the same isolated node passed 1/1 through formal commit and lost-completion exact receipt replay; no shared database or runtime mutation occurred.

## P0-20260825-PARALLEL-V4-FIRST-TURN-ACTIVITY-FAILED-BEFORE-FRAMES

- Severity: P0
- Status: OPEN / UAT_BLOCKED / ROOT_CAUSE_CONFIRMED
- Component: Target Intake parallel V4 first ROOM_MESSAGE execution
- Confirmed fact: On the fresh activation bound to candidate `0b46894a923276e8a065f1b8cb6ff3d465755282`, the only submitted USER ROOM_MESSAGE returned POST 201, then its SSE stream emitted only V4 sequence `0` as `error` after approximately 8.44 seconds. No DIALOGUE, DOSSIER, or QUALITY substantive frame was observed, and the UI terminated as `AGENT_RUN_ACTIVITY_FAILED`.
- Root cause and evidence: The first failing boundary is Java V4 Frame admission after PREPARE and before the EXECUTE fan-out. The mTLS access log records three successful 48-byte PREPARE responses, one per Temporal retry, followed by no EXECUTE/provider/frame activity. The persisted command binds `room_id=ROOM_7ae25250b86147b6b58beeea312589eb`, while the AgentRun correctly stores that value in `agent_run.room_id` and separately stores the epoch identity `CRE_57d8362a8ef341f38717d50468f44c4b` in `agent_run.room_epoch_id`. Both `JdbcIntakeParallelFrameAdmissionAuthorityResolver` and `JdbcIntakeParallelFrameStagingStore` compare the command/admission `roomId` against `run.room_epoch_id`, so a valid production command deterministically fails authority comparison before creating a Frame set. The fixtures had not distinguished the room ID namespace from the room-epoch ID namespace. The run had no Provider call, Frame row, READY artifact, FINAL, or formal write; therefore this is not model-output instability.
- Impact: The affected activation cannot produce a first packet, measure three-lane completion, assemble a proposal, or formally commit the first party turn; downstream UAT remains blocked until a fresh activation validates the repair.
- Confirmed verification: The focused resolver and staging-store contract nodes passed 8/8. They distinguish `room_id` from `room_epoch_id`, accept the exact persisted room ID, reject an epoch identity substituted as a room ID, and preserve the adjacent V4/V080 staging invariants.
- Reverification: A fresh activation bound to `56f14996f377b19e3e81cdd3f603a10540fbf099` passed deployment health, but the only USER ROOM_MESSAGE in `CASE_P9_6A8DAB23_1` again produced no substantive Frame and terminated as `AGENT_RUN_ACTIVITY_FAILED` approximately 10.39 seconds after POST 201. The message was not replayed and no second case was created. The room/epoch repair did advance the command into Python Graph execution: the Graph command and its only attempt are `EXECUTING`, Provider call count is zero, and Java created one exact-three Frame set with three `ADMITTED` slots. The command acquired the thread-scoped lease at fencing token `2`, because the preceding opening turn had already consumed token `1`. The initial receipt boundary, `ParallelReceiptExecutionRecord`, the G012 row check and its insert trigger all use the absolute thread-scoped fencing token to decide whether a receipt has a predecessor: they allow predecessor-free initial binding only at token `1`. A focused token-2 initial-receipt proof therefore fails in the record constructor before the transaction, while the persisted production attempt is blocked by the equivalent database authority rule. No receipt-execution row, Provider call, Frame result, or public packet exists. Subsequent same-attempt startup observes an already executing command without a bound receipt and is rejected as `GRAPH_NEW_AGENT_ATTEMPT_REQUIRED`. This is a receipt-lineage/thread-fencing namespace bug, not model-output instability.
- Receipt-lineage verification: The complete gateway and ledger unit files passed, including a predecessor-free initial receipt at thread fence `2` and adjacent exact-predecessor takeover/replay behavior. The SQL contract file passed 14/14 and the migration-loader file passed 26/26. An isolated PostgreSQL integration applied G001 through G014, replayed all fourteen migrations as already current, and passed restore/readiness/runtime-ACL validation without SQL, owner, constraint, or trigger errors.
- Third UAT observation: Fresh activation `p9act.v1.b5077dc822ea00ea4794d202ee28e7fc`, built from `6c1c125ecdf30050b79eed6af1d45a5b5011512d`, passed launcher deployment and opening-turn readiness. The only USER ROOM_MESSAGE in `CASE_P9_6A8DB464_1` moved the UI to Intake Error approximately 9.82 seconds after POST 201 without adding an Agent reply. The case was frozen immediately; the message was not replayed and no second case was created. Java durably created one COLLECTING Frame set and three ADMITTED generation/slot pairs within 22 ms, but Python persisted no receipt execution, receipt cycle, ingress, Frame result, proposal artifact, FINAL, RESULT_READY, or formal write; Provider call count remained zero. The first exact backend failure is PostgreSQL `InsufficientPrivilege` at `store_parallel_receipt_execution`: the INSERT trigger `guard_agent_graph_parallel_execution_insert()` invokes `require_parallel_intake_graph_command()`, but `graph_runtime` has INSERT/SELECT on the target table and no EXECUTE privilege on that function. All three Activity executions ended at the same pre-Provider ACL boundary, after which the only V4 public event was terminal ERROR sequence zero. This is a Graph migration ACL omission, not model-output instability.
- ACL verification: The migration and readiness unit files passed 46/46. An isolated PostgreSQL integration proved that the runtime role can execute the exact three-`varchar` `require_parallel_intake_graph_command` helper and receives `false` for a missing command, while existing DELETE, DDL, append-only and non-allowlisted function restrictions remain enforced. Readiness now fails closed when the exact helper privilege is absent, has a different signature, or is SECURITY DEFINER; G001-G014 first application and replay remain unchanged.
- Fourth UAT observation: Fresh activation `p9act.v1.07216a7ae3a09cdf6f8724df913d6ab7`, built from `e07063db3f9c3c8560ceecc07fba45dda08a6bfa`, passed deployment health, G014 readiness, and the exact runtime-function privilege check. The only USER ROOM_MESSAGE in `CASE_P9_6A8DBA90_1` failed without any substantive Frame approximately 13.10 seconds after POST 201. The case was frozen immediately; the message was not replayed and no second case was created. The prior ACL failure did not recur: one predecessor-free receipt execution was persisted at thread fence `2`. Failure then occurred after that receipt binding and before the first Frame/provider start. Python returned HTTP 409 with a 52-byte body; that exact wire length corresponds to the generic `GRAPH_CONTRACT_REJECTED` response, while the Java execution boundary retained only `AGENT_RUN_ACTIVITY_FAILED`. The immutable command proves this Intake room has the valid zero-based `room_epoch=0`. The next live boundary constructs the Provider-group scope through `GraphBulkheadScope.from_graph_identity`, whose local validation rejects every epoch below `1`; it therefore raises before calling the permit SQL. This is confirmed by the absence of any fanout-permit row, while the Frame set remains COLLECTING with three ADMITTED slots, one receipt execution, zero receipt cycles, zero Provider calls, zero Frame results, zero READY/proposal/RoomGraphResult artifacts, zero FINAL/RESULT_READY, and zero formal writes. The root cause is a zero-based room-epoch versus one-based bulkhead validation contract mismatch, not model output or scheduling instability.
- Identifying metadata: observed 2026-08-25 through 2026-08-26; first activation `p9act.v1.69524e0d05f770dcb60c9b30a4201b7a`, case `CASE_P9_6A8DA486_1`; second activation `p9act.v1.8088ed3e050e3798a007808ea3d0653d`, case `CASE_P9_6A8DAB23_1`; third activation `p9act.v1.b5077dc822ea00ea4794d202ee28e7fc`, case `CASE_P9_6A8DB464_1`; fourth activation `p9act.v1.07216a7ae3a09cdf6f8724df913d6ab7`, case `CASE_P9_6A8DBA90_1`; each run used one USER ROOM_MESSAGE only and no failed message was replayed.

## P0-20260826-PARALLEL-EXECUTION-FAILURE-LEAVES-SPLIT-TERMINALS

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake parallel Graph execution cleanup and Java V4 staging terminalization
- Confirmed fact: After the only V4 USER turn in `CASE_P9_6A8DBA90_1` failed, the Java AgentRun and attempt were `FAILED/UNCOMMITTED`, while the bound Graph command and attempt remained `EXECUTING`, the Graph lease continued renewing for more than 17 minutes, and the V081 frame set remained `COLLECTING` with all three generations and slots `ADMITTED`.
- Root cause and evidence: Python acquires the execution/receipt before all live authority and request checks have entered a failure cleanup boundary. In the exact observed path, `_execute_live` starts the execution heartbeat and then evaluates the provider bulkhead scope before its `try/finally`; the legal zero-based room epoch raises there, leaving the heartbeat task and execution lease active. The adjacent post-acquire authority and request-selection checks in `open_stream` are likewise outside the call to `_finish_failed_execution`. On the Java side, PREPARE has already persisted the V081 admission, but an EXECUTE/Activity failure only terminalizes the Java AgentRun; the staging port has no production failure/cancel operation and no receipt-bound Graph negative acknowledgement is issued. `intake_parallel_stream_service.py:409-428,646-675,822-850`, `HttpTargetE2EIntakeParallelFrameExecutionClient.java:182,247`, `IntakeParallelFrameStagingPort.java:27`, `ExecuteAgentRunActivityImpl.java:312`, and `V081__intake_parallel_frame_staging.sql:149` expose the split.
- Impact: Any authority, protocol, transport, or worker failure after parallel admission and before a durable Frame terminal can strand Graph execution authority, continue lease renewals, retain three non-terminal V081 slots, and prevent deterministic same-command recovery or cleanup.
- Identifying metadata: activation `p9act.v1.07216a7ae3a09cdf6f8724df913d6ab7`; case `CASE_P9_6A8DBA90_1`; run `target-intake-run:8e98cfbecdb53da4b46b9c2dfe0d00fb`; attempt `target-intake-attempt:8e98cfbecdb53da4b46b9c2dfe0d00fb:1`; frame set `IFS_bcfa7bec7185cfdff7d09817a6479c45`; observed 2026-08-26.
- Regression fact update: Fresh activation `p9act.v1.1724c4fe5fc2bd01cf5ff979a66bd7a3` at candidate `2887ac0da5fe41f7d71d6c65b44d09e2173303cf` admitted the only USER parallel turn in `CASE_P9_6A8DFAEB_1`, invoked all three Providers once, then left all three generation-1 slots `STARTED` with zero sealed results, zero assembly READY, zero FINAL, and zero formal reply. Dossier had four durable public items, Quality had six, and Dialogue had none; the Java run terminated `ABORTED/UNCOMMITTED` while the Graph command/attempt and V081 Frame set remained non-terminal.
- Regression metadata update: observed 2026-08-26; run `target-intake-run:dd585fa0e34c31f28df402a6663f8dc4`; attempt `target-intake-attempt:dd585fa0e34c31f28df402a6663f8dc4:1`; command `intake-message:dd585fa0e34c31f28df402a6663f8dc4`; frame set `IFS_13aa912872515077aa521b501ba08ac9`; browser submit time `2026-08-25T20:35:23.483Z`.
- Source-boundary fact update: `ParallelIntakeFrameOrchestrator.execute_frame()` validates the terminal checkpoint, reconstructs its proof, constructs `FrameSealed`, emits the seal, and revalidates the result only after the child graph has returned `COMPLETE`; this whole post-COMPLETE sequence was outside the model invocation's `FrameInterrupted` exception boundary. Any checkpoint-proof, terminal-schema, or sink failure in that interval therefore escaped as a generator failure without a lane terminal, reproducing the same split-terminal shape independently of Provider success.
- Verification fact: The focused Python post-COMPLETE, failed-lane isolation, and checkpoint replay nodes passed 3/3; the Java HTTP client, staging authority, V4 ledger protocol, and typed gateway selectors passed 31/31. Read-only reverse review found no remaining blocker in the reviewed Frame terminal, subset retry, or V4 failure-mapping boundaries. Runtime UAT remains pending.
- Regression fact update: On activation `p9act.v1.9cd9a8bb2c8adcc8752e9cb08aaf4685`, the only parallel USER turn in `CASE_P9_6A8E2926_1` ended after a Dialogue generation-1 `OUTPUT_SCHEMA_INVALID` and one lane reset. Java converged the bound AgentRun to `ABORTED/UNCOMMITTED`, but the exact Graph command and attempt remained `EXECUTING`, the fanout permit remained `GRANTED`, and the V081 Frame set remained `COLLECTING`. The expired lease was not sufficient to close those other authorities.
- Final-retry ownership evidence: The Python streaming adapter now closes the inner parallel generator when the HTTP stream is abandoned, so live disconnect paths can reach the existing Graph execution-finally boundary. The remaining split is specific to final Activity retry exhaustion: intermediate transport failures correctly preserve the same V081 `COLLECTING` Frame set for same-command continuation, but after the last retry `ExecuteAgentRunActivityImpl` persists the Java `FAIL_LOGICAL_RUN` result without issuing any receipt-bound Graph terminal acknowledgement and without transitioning the exact V081 Frame set. The Java run can therefore become `ABORTED/UNCOMMITTED` while Graph command/attempt/permit and V081 stay non-terminal even though no further retry is possible. Confirmed boundaries are `ExecuteAgentRunActivityImpl.java:285,312,347`, `HttpTargetE2EIntakeParallelFrameExecutionClient.java:193,214,223`, `JpaAgentRunLedger.java:530,607`, `JdbcIntakeParallelFrameStagingStore.java:527`, `gateway.py:1858,1932`, and the G005 fanout-permit routine.
- Cleanup-gate evidence: G015 read-only preflight proved the active Graph lifecycle is the first deletion blocker and therefore rejected physical purge before any Java or Graph mutation. Exact retained identities are run `target-intake-run:68090149a99e30fb833e0678b0f6ee75`, attempt `target-intake-attempt:68090149a99e30fb833e0678b0f6ee75:1`, command `intake-message:68090149a99e30fb833e0678b0f6ee75`, thread `grt.v1.01a03b5aeedc7f908e3e4edf09ad34d7`, and Frame set `IFS_31e5011ab1c0f9a3dda91b650ef9bcf1`. No purge receipt, Java deletion, Graph deletion, deployment, Provider call, or browser mutation occurred during this audit.
- Termination-replay fact update: The current Java final-retry termination path calls the complete `PREPARE` routine again. That routine obtains the remote prepared authority, then re-enters `staging.admit(...)` and `staging.planExecution(...)`, and only afterwards constructs the admission receipt required by `TERMINATE`. A deterministic staging/planning failure can therefore recur before the terminal request is sent, so the exact Graph acknowledgement and the local failure transaction cannot be reached. Confirmed boundaries are `HttpTargetE2EIntakeParallelFrameExecutionClient.java:258-267,338-378`; the termination method currently has no immutable admission-receipt reader independent of planning.
- Focused verification update: The failure path now persists immutable admission history/current authority, reads that persisted receipt for `TERMINATE` without rerunning preparation, records the Graph failure receipt first, and then commits the Java AgentRun/attempt failure, one V4 `ERROR`, and the bound V081 `FAILED_UNCOMMITTED` transition in one local transaction. On their final executions, the focused Activity, profile selector, HTTP client, gateway, V081 staging, failure-store, and V091 migration classes passed 58/58; the three conflict-provenance methods also executed and passed. Runtime activation and UAT remain pending.

## P1-20260826-PARALLEL-CREATE-NEXT-ATTEMPT-USES-TERMINAL-COMMITTER

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: V4 AgentRun Activity retry transition and terminal failure persistence
- Confirmed fact: `ExecuteAgentRunActivityImpl` creates a retryable `CREATE_NEXT_ATTEMPT` result for an intermediate attempt and then unconditionally invokes `AgentRunTerminalFailureCommitter`. The transactional implementation requires an external Graph termination receipt for every parallel request, while the V4 ledger terminal boundary accepts only non-retryable `FAIL_LOGICAL_RUN`; the legitimate intermediate retry therefore reaches a terminal-only contract.
- Root cause and evidence: The failure source and termination code are not used to select the persistence owner. `ExecuteAgentRunActivityImpl.java:349-379,433-468`, `TransactionalAgentRunTerminalFailureCommitter.java:47-57`, and `JpaAgentRunLedger.java:607-618` expose the mismatch.
- Impact: A retryable single-lane/attempt transition can be rejected before the next attempt is allocated, so the parallel lane cannot exercise its intended isolated retry path.
- Identifying metadata: confirmed by read-only reverse review of the uncommitted final-retry milestone on 2026-08-26; no runtime mutation or Provider call was used.
- Focused verification update: V4 `CREATE_NEXT_ATTEMPT` is no longer sent through the terminal committer; the same-command parallel lane retry remains on its non-terminal retry path, while only a non-retryable logical failure with a persisted external termination receipt reaches terminal persistence. The final Activity suite passed 19/19, including adjacent retry and terminal paths. Runtime UAT remains pending.

## P1-20260826-PARALLEL-JAVA-CONFLICTS-MASQUERADE-AS-GRAPH-FAILURE

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Parallel exact-three assembly, V4 terminalization, and failure provenance
- Confirmed fact: Post-seal Java `AssemblyConflictException` and `TerminalConflictException` are converted to logical Graph failures. The Activity then treats those converted failures like Graph execution failures and can issue external `TERMINATE` even though all three Graph lanes have already sealed.
- Root cause and evidence: The gateway result path collapses Java-local assembly/terminal conflicts into the same `AgentRunExecutionException` shape as remote Graph failure, and the final-retry path has no retained provenance discriminator. Confirmed boundaries are `TargetE2EIntakeParallelExecutionGateway.java:136-155,166-193` and `ExecuteAgentRunActivityImpl.java:286-309,369-420`.
- Impact: A Java-local reconciliation or assembly problem can incorrectly abort already completed Graph authority, obscure the first failing boundary, and leave Java/Graph terminal facts inconsistent.
- Identifying metadata: confirmed by read-only reverse review of the uncommitted final-retry milestone on 2026-08-26; no runtime mutation or Provider call was used.
- Pre-commit audit update: The assembly and terminal conflict branches now retain `LOCAL_RECONCILIATION` provenance, but `HttpTargetE2EIntakeParallelFrameExecutionClient` still catches Java-local `StagingConflictException` during technical preparation or stream ingress and wraps it as `TargetE2EGraphClientException.protocol`. The parallel gateway therefore still converts a local staging/CAS conflict into execution-owned `FAIL_LOGICAL_RUN`, allowing the Activity to call external Graph `TERMINATE`. Confirmed boundaries are `HttpTargetE2EIntakeParallelFrameExecutionClient.java:234-236,396-398`, `TargetE2EIntakeParallelExecutionGateway.java:73-80`, and `ExecuteAgentRunActivityImpl.java:372-376`.
- Focused verification update: Staging, assembly, and terminal conflicts now retain `LOCAL_RECONCILIATION` provenance through the HTTP client, gateway, and Activity. A local conflict before public output does not invent terminal authority, a post-seal local conflict does not abort Graph authority, and neither path invokes Graph `TERMINATE`. `parallelLocalStagingConflictNeverTerminatesGraphBeforeAnyPublicEvent`, `localStagingConflictRetainsLocalAuthorityWithoutInventingACompletion`, and `planningConflictAfterAdmissionRetainsLocalReconciliationAuthority` all executed and passed; their three containing suites passed 38/38. Runtime UAT remains pending.

## P1-20260826-PARALLEL-REMOTE-ERROR-AUTHORITY-IS-LOST

- Severity: P1
- Status: OPEN / ROOT_MECHANISM_FIXED / UAT_PENDING
- Component: Target E2E parallel Graph HTTP client, execution gateway, and AgentRun Activity
- Confirmed fact: The parallel HTTP client parses the remote `{code,retryable}` envelope, but its gateway does not map the client exception to the typed AgentRun execution error used by the legacy lane. A non-retryable Graph 409 therefore reached the Java ledger as generic `AGENT_RUN_ACTIVITY_FAILED` and was invoked three times instead of preserving the original safe code and retry decision.
- Root cause and evidence: `HttpTargetE2EIntakeParallelFrameExecutionClient.java:858` retains the remote classification; `TargetE2EIntakeParallelExecutionGateway.java:74` does not translate it; `ExecuteAgentRunActivityImpl.java:249` treats the untyped runtime exception as a generic same-command retry. The current UAT retained only the generic Java code while the 52-byte Graph response corresponded to `GRAPH_CONTRACT_REJECTED` with `retryable=false`.
- Impact: Diagnostics lose the first failing contract, non-retryable errors consume redundant HTTP/Activity attempts, and recovery policy can diverge from the signed Graph response.
- Identifying metadata: same activation/case/run as `P0-20260826-PARALLEL-EXECUTION-FAILURE-LEAVES-SPLIT-TERMINALS`; observed three EXECUTE attempts and one generic durable Java failure on 2026-08-26.
- Regression fact update: The same failure recurred in `CASE_P9_6A8DFAEB_1`: Java entered `EXECUTE_OR_RECONCILE` three times for the same V4 attempt and retained only `AGENT_RUN_ACTIVITY_FAILED / AgentRun V3 logical execution cannot continue`, while the three current Frame slots remained generation 1 `STARTED` and no FINAL existed. The deepest Java exception retained before child-checkpoint inspection was the generic `AgentRunRetryableFailure: agent run infrastructure failure`.
- Regression metadata update: activation `p9act.v1.1724c4fe5fc2bd01cf5ff979a66bd7a3`; run `target-intake-run:dd585fa0e34c31f28df402a6663f8dc4`; attempt `target-intake-attempt:dd585fa0e34c31f28df402a6663f8dc4:1`; observed 2026-08-26.
- Verification fact: `TargetE2EIntakeParallelExecutionGatewayTest` passed 5/5, including preservation of an existing remote safe code and explicit V4 handling of unclassified failures before and after durable public output. The adjacent HTTP client selector passed 8/8. Runtime UAT remains pending.

## P1-20260826-PARALLEL-STARTED-FRAME-HAS-NO-RECOVERY-STATE

- Severity: P1
- Status: FIXED / FOCUSED_CHECK_PASSED / FRESH_MIGRATION_PASSED / UAT_PENDING
- Component: Java V4 Frame staging plan/replay
- Confirmed fact: A durable `PUBLIC_FRAME_START` changes a Frame slot to `STARTED`, but a later EOF or worker loss before `INTERRUPTED/SEALED` has no adoption or ambiguity transition. Replanning the same immutable command rejects every `STARTED` slot as `INTAKE_PARALLEL_EXECUTION_STARTED_AMBIGUOUS`.
- Root cause and evidence: `HttpTargetE2EIntakeParallelFrameExecutionClient.java:564` persists the START prefix before later events; `JdbcIntakeParallelFrameStagingStore.java:722` unconditionally rejects the persisted STARTED state. The existing partial-EOF coverage proves the prefix remains durable but does not prove a subsequent recovery transition.
- Impact: Any lane connection loss after its public START and before its terminal event can permanently block the Intake turn even when its two sibling lanes are sealed and the command/request authority is unchanged.
- Identifying metadata: confirmed by static production call-chain audit at HEAD `e07063db3f9c3c8560ceecc07fba45dda08a6bfa`; no runtime mutation was used.
- Verification fact: Graph G016 now freezes one immutable stale-execution abandonment under the predecessor receipt fence; Java V092 and the staging store accept that proof only for exact current `STARTED` lanes, advance only those lanes to `AMBIGUOUS`, then publish a successor receipt that leaves sealed siblings unchanged. The focused HTTP successor test, its 9 adjacent HTTP cases, the signer suite, the JDBC staging contract, and the V092 contract passed. A fresh PostgreSQL schema then applied all 101 migrations through `V092`, replayed with zero pending migrations, and exposed the abandonment authority table. Runtime UAT remains pending.

## P1-20260826-PARALLEL-GENERATION-RESET-HANDOFF-ABORTS-EXECUTION

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Java V4 parallel HTTP execution and Python Graph lane-generation handoff
- Confirmed fact: In the first substantive parallel Intake turn after activation `p9act.v1.6f841bbd2aa0bfcd44df8d423ed8ad7a`, all three generation-1 lanes started and emitted public projections. Dialogue then durably emitted `public_frame_interrupted` for `OUTPUT_SCHEMA_INVALID` followed by `frame_generation_reset` from generation 1 to generation 2, but no generation-2 `public_frame_start` was persisted.
- First-loss boundary and evidence: The V4 event ledger reached sequence 11 for the Dialogue interruption and sequence 12 for its reset. The Java AgentRun Activity failed immediately afterward as `AgentRunRetryableFailure: agent run infrastructure failure`; two same-command Activity retries did not admit the replacement generation, and external failure terminalization was rejected after a Python stream request returned HTTP 503. The two sibling lanes were not reset, so the loss is between accepted lane-local reset authority and replacement-generation execution rather than a whole-frame-set reset.
- Root cause: The first provider generation, generation-reset emission, and replacement provider call were executed inside one LangGraph `invoke_model` node. LangGraph had no checkpoint between the accepted reset and the replacement call. Cancellation during replacement left the durable child checkpoint at generation 1 / `AUTHORIZED`; same-command re-entry then rejected that incomplete checkpoint before another provider call. The later HTTP 503 was a separate fail-closed `GRAPH_LEASE_UNAVAILABLE` response because the original execution lease was still valid.
- Impact: The AgentRun remains `RUNNING / UNCOMMITTED`, the frame set remains `COLLECTING`, no formal Intake reply or dossier revision is written, and a legitimate single-lane Schema retry blocks the entire new Intake room.
- Identifying metadata: observed 2026-08-26; deleted test case `CASE_P9_6A8EA312_1`; run `target-intake-run:c1c5c2b3fd113895af5becf2c4b9a256`; attempt `target-intake-attempt:c1c5c2b3fd113895af5becf2c4b9a256:1`; command `intake-message:c1c5c2b3fd113895af5becf2c4b9a256`; frame set `IFS_75aeda9235646fa41fa2c386005ead7f`.
- Verification fact: Generation reset and replacement execution now occupy separate durable child-graph checkpoints. Focused tests proved lane-local reset isolation, one-call replacement recovery, aggregate provider usage, exact successor rejection, legacy `v1` terminal replay, and strict `v2` split-usage validation. Two isolated PostgreSQL 16 tests proved recovery after a replacement-start disconnect and after transition events were accepted before the transition node checkpointed; reconnect/replay did not add a provider call and left no test container behind. Runtime UAT remains pending.
- Runtime recurrence: Fresh activation `p9act.v1.850c3ce175c96a5bfd2f8873abe7aef3` at candidate `02931e546a7aaef0919eead9b72a2d43ac1f9935` reached the first USER `agent-stream.v4` turn in `CASE_P9_6A8EB25C_1`. QUALITY generation 1 sealed normally. DIALOGUE and DOSSIER generation 1 each ended `OUTPUT_SCHEMA_INVALID`; each child graph durably advanced through `RESET_DETECTED` to a step-3 `RETRY_AUTHORIZED` checkpoint whose next node is `invoke_replacement_model`, and Java advanced only those two current slots to generation 2 `ADMITTED`. Neither replacement emitted `public_frame_start` or entered `STARTED` for more than 100 seconds, while QUALITY remained sealed and was not replayed. The run therefore remained `RUNNING / UNCOMMITTED`, the frame set remained `COLLECTING`, and no formal turn committed. This proves the checkpoint split survives in production but does not yet prove that the same live invocation can enter the replacement node; the focused reconnect tests did not cover this live post-reset scheduling boundary.
- Recurrence metadata: run `target-intake-run:161aa6b7714d389885246af2a83a4f17`; attempt `target-intake-attempt:161aa6b7714d389885246af2a83a4f17:1`; command `intake-message:161aa6b7714d389885246af2a83a4f17`; frame set `IFS_3a0a740f0ccbe6097b3af7b076bc2d21`; thread `grt.v1.01a03d6fa9367c96905a9054d12b458f`.
- Confirmed recurrence root cause: The live service pre-emitted each generation-1 `public_frame_start` and then set `emit_start=false` on all child requests. The replacement node was executed and called the model, but inherited the same flag and omitted generation 2 `public_frame_start`. The strict stream validator resets the lane to `started=false` after `frame_generation_reset`, so it rejected the first generation-2 projection before Java could move the slot from `ADMITTED` to `STARTED`. A no-Provider replay observed two model-runner calls but only generation-1 start, interruption, and reset events, ending at the same `RETRY_AUTHORIZED` checkpoint.
- Recurrence-fix verification fact: The production-shaped protocol-validator regression proved one pre-emitted generation-1 start followed by exactly one generation-2 replacement start before its first projection. The adjacent lane-isolation and checkpoint-resume selectors also passed; all three focused nodes completed without worktree mutation.
- Second runtime recurrence: Fresh activation `p9act.v1.30b5b8a8790c26a76351e2b1f3ceff2b` at candidate `71a66b3daaab0c974b1620842026f073276ec50b` reached the first USER V4 turn in `CASE_P9_6A8EBA42_1`. Dialogue generation 1 emitted two projections, then a durable `OUTPUT_SCHEMA_INVALID` interruption and generation reset. The child checkpoint advanced to `RETRY_AUTHORIZED`, generation 2, with next node `invoke_replacement_model`, while the Java slot advanced to generation 2 `ADMITTED`. The same HTTP request then ended normally with HTTP 200 before any generation-2 start. Dossier and Quality remained generation-1 `STARTED` with no result, and the frame set remained `COLLECTING` with zero proposal/result artifacts.
- Second-loss root cause and evidence: A production saver replay proved the Dialogue child graph resumes from `RETRY_AUTHORIZED` into the replacement node and seals generation 2, excluding checkpoint routing as the loss boundary. The live parallel runner, its lease/provider heartbeats, provider permit, and exact-fence cleanup were owned only by the HTTP async iterator's `except/finally`. A partial-consumer proof showed that abandoning that iterator without delivering `aclose` leaves the runner active, the provider permit unreleased, and the admission token held; explicit `aclose` performs the cleanup. The three lanes stopping together after one accepted lane-local reset is therefore caused by missing execution ownership independent of response iteration, not by a shared model-reset state.
- Second recurrence metadata: run `target-intake-run:835b18a9008639e7a673fefd540c37d1`; attempt `target-intake-attempt:835b18a9008639e7a673fefd540c37d1:1`; command `intake-message:835b18a9008639e7a673fefd540c37d1`; frame set `IFS_585e2b2f1e469084987257b451510ef4`; thread `grt.v1.01a03d8bbbb27842ac30e67a9abdb24b`.
- Second-loss focused verification fact: The service-owned stream regression consumed only through a Dialogue generation reset, then stopped polling while the replacement generation and both sibling lanes reached technical completion; generation 2 emitted its own start and seal, and the admission permit was released. The bounded event/byte backlog, retained cleanup-failure visibility, and post-prefix authority-bound failure record regressions passed. The focused Python selection completed `37 passed`, and the Java HTTP transport selection completed `11 passed` with no failures.

## P0-20260826-PARALLEL-FAILURE-TERMINALIZATION-DB-PRIVILEGE

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Python Graph bulkhead transaction terminalization and Java V4 external-failure convergence
- Confirmed fact: Fresh activation `p9act.v1.3792230b03de8e4e0108bfd1784f8d00` at candidate `68e6221232e16069c40686af6e80d6babe32c264` reached the first USER V4 turn in `CASE_P9_6A8ECC8A_1`. Dialogue generation 1 emitted projections and reset to generation 2; the Java slot advanced to generation 2 `ADMITTED`, while Dossier and Quality remained generation 1 `STARTED`.
- Failure evidence: The Python service logged `target-E2E graph stream startup failed` with `error_type=InsufficientPrivilege` at `app.graph_runtime.postgres_bulkhead:terminalize_transaction:624`. The affected HTTP stream retry returned 500. Java then attempted external failure terminalization on three Activity attempts; each was rejected as a sanitized `TargetE2EGraphClientException`, leaving the AgentRun `RUNNING / UNCOMMITTED` and the frame set `COLLECTING` with no proposal or graph-result artifact.
- Root cause and evidence: `graph_runtime` intentionally had only `SELECT` on `agent_graph_fanout_permit`, while `terminalize_command_permits` issued a direct `SELECT ... FOR UPDATE` before calling the existing owner function. PostgreSQL requires table `UPDATE` privilege for that row lock, so the least-privilege grant contract and the Python transaction were incompatible. The focused PostgreSQL test now proves the runtime role still lacks table `UPDATE` while exact command permit terminalization and replay both succeed.
- Impact: The first substantive Intake turn cannot converge to either a committed proposal or a durable failed terminal state, so the new parallel Intake lane and full-chain UAT are blocked before Intake completion.
- Metadata: case `CASE_P9_6A8ECC8A_1`; run `target-intake-run:da9d2f78fbf43043a8585139cce0cb1c`; frame set `IFS_a62f92a6bebadb47cf11520f5a348a6e`; activation generation `1787743370`; unit verification `30 passed`; isolated PostgreSQL integration verification `1 passed`.

## P1-20260826-GRAPH-PARALLEL-PURGE-CYCLE-LINEAGE-INVALID

- Severity: P1
- Status: FIXED / FOCUSED_CHECK_PASSED
- Component: Target-E2E Graph physical test-thread purge
- Confirmed fact: The retained Graph purge function cannot delete a thread that contains a parallel receipt cycle. Its cycle loop references `agent_graph_parallel_receipt_cycle.predecessor_cycle_id`, but that column exists only on `agent_graph_parallel_receipt_execution`; the cycle table has no predecessor column.
- Root cause and evidence: `G015_target_e2e_test_thread_purge.sql:446-455` queries the nonexistent cycle column. The persisted lineage alternates execution-to-cycle and successor-execution-to-predecessor-cycle foreign keys, while the function tries to delete all cycles before all executions. The focused purge fixture at `test_graph_postgres_runtime.py:3721-3726` records zero parallel cycles and executions, so this path was not exercised.
- Impact: A terminal Target-E2E test thread containing any retry receipt cycle cannot be physically purged through the reviewer-authorized boundary; the transaction fails before removing its exact Graph closure.
- Identifying metadata: confirmed by read-only source and migration audit at HEAD `006ad02d96fe21d08537a4a0ec45dffaa8177962`; no database or runtime mutation was used.
- Verification fact: G016 replaces the invalid two-pass deletion with one leaf-pruning lineage loop and includes abandonment rows in exact purge counts. The migration/runtime-ACL integration node and the reviewer-owned exact purge integration node both passed against real PostgreSQL on 2026-08-26 (`2 passed`).

## P2-20260826-PARALLEL-LANE-QUEUE-UNDERBUDGETS-CONTROL-EVENTS

- Severity: P2
- Status: OPEN / REPAIR_PENDING / SOURCE_CONFIRMED
- Component: Python fair three-lane merge queue
- Confirmed fact: The per-lane queue capacity is 32, while a valid Dossier Frame may emit 32 projection items and also emits control events through the same non-blocking queue.
- Root cause and evidence: `_FairFrameMergeQueue` uses `put_nowait` and raises at capacity in `intake_parallel_stream_service.py:229-267`; `parallel_outputs.py:131-137` permits 32 Dossier projection items; Frame control events use the same queue in `intake_parallel_stream_service.py:701-723` and `parallel_graph.py:724-732`.
- Impact: A valid maximum-cardinality, fast-producing lane can fail due to scheduler timing even though its output satisfies the registered Schema and item budget.
- Identifying metadata: confirmed by static production call-chain audit at HEAD `e07063db3f9c3c8560ceecc07fba45dda08a6bfa`; no test or runtime mutation was used.

## P2-20260826-PARALLEL-CACHED-COMPLETION-ACCEPTS-INTERRUPTED-LANE

- Severity: P2
- Status: OPEN / REPAIR_PENDING / SOURCE_CONFIRMED
- Component: Python cached parallel technical-completion replay
- Confirmed fact: The v1 cached-completion replay validates the active Frame set but does not require every lane terminal to be `SEALED`; the shared event validator accepts `INTERRUPTED` as a lane terminal.
- Root cause and evidence: `intake_parallel_stream_service.py:1325-1329` omits an exact-three sealed assertion; `intake_parallel_stream.py:380-389` accepts sealed or interrupted lane terminals; normal completion construction is stricter in `intake_parallel_runtime.py:201-221`; the G012 completion guard validates outer identity but not nested lane terminal kind.
- Impact: Malformed legacy, cross-version, or anomalously persisted technical completion data can be replayed as completed even though one lane never produced a sealed result.
- Identifying metadata: confirmed by static production call-chain audit at HEAD `e07063db3f9c3c8560ceecc07fba45dda08a6bfa`; normal fresh producers were not observed generating this state.

## P0-20260826-PARALLEL-QUALITY-PROJECTION-ORDER-REJECTED

- Severity: P0
- Status: FIXED / FOCUSED_CHECK_PASSED / UAT_PENDING
- Component: Intake parallel `QUALITY_FRAME` public projection contract
- Confirmed fact: On fresh activation `p9act.v1.71326c7df99d5578916b93c6efa57936`, the only USER ROOM_MESSAGE in fresh case `CASE_P9_6A8DC7A7_1` started all three V4 lanes within approximately 2.3 ms and invoked each Provider once, but `QUALITY_FRAME` failed before persisting any projection or sealed result with `INTAKE_PARALLEL_QUALITY_SCORE_ORDER_INVALID`.
- Root cause and evidence: The Quality lane emitted a public projection prefix that did not match the fixed `QUALITY_DIMENSION_ORDER`. `_validate_public_projection_prefix` rejected it at `python-agent-service/app/graphs/intake/parallel_graph.py:1050` before the offending item entered the durable ingress ledger. The parent Graph command then aborted as `INTAKE_PARALLEL_FRAME_BATCH_FAILED / TECHNICAL_FRAME_FAILURE`; the Frame set contains three failed slots, zero results, and no READY, proposal artifact, RoomGraphResult artifact, FINAL, RESULT_READY, formal commit, dossier/matrix revision, or Agent reply.
- Impact: The first substantive parallel Intake turn cannot seal its exact-three result or commit any business state, blocking response-time acceptance and all downstream UAT stages.
- Identifying metadata: observed 2026-08-26; candidate `3b8ca8df958b895c1b0b36c09a960e00b3789903`; case `CASE_P9_6A8DC7A7_1`; message `MESSAGE_262690d15cc44d55bc841b72a9cb5d61`; run `target-intake-run:04d1332aa7a039fcaccbb513e4a2070b`; attempt `target-intake-attempt:04d1332aa7a039fcaccbb513e4a2070b:1`; command `intake-message:04d1332aa7a039fcaccbb513e4a2070b`; Frame set `IFS_0cc2b0f86cd625d0a04acb7fcc66919d`; first Quality failure approximately 5.723 seconds after browser submit.

## P0-20260826-PARALLEL-DIALOGUE-PROJECTION-SLOT-REPEATED

- Severity: P0
- Status: FIXED / FOCUSED_CHECK_PASSED / UAT_PENDING
- Component: Intake parallel `DIALOGUE_FRAME` public projection streaming
- Confirmed fact: In the same single-turn UAT, `DIALOGUE_FRAME` durably accepted one substantive projection and then failed with `INTAKE_PARALLEL_FRAME_PROJECTION_SLOT_REPEATED`; it did not seal a Frame result.
- Root cause and evidence: The Dialogue lane attempted to emit a second public projection for a slot already occupied in the same generation. The V4 ingress boundary rejected the duplicate after one accepted projection; the lane was subsequently marked FAILED and the parent exact-three batch could not complete. The runtime recorded no lane retry or reset and `provider_call_count=3` for the whole Graph attempt.
- Impact: Even if the independent Quality ordering failure were absent, the Dialogue lane would still prevent exact-three sealing, Java assembly READY, FINAL, RESULT_READY, and the single formal Intake write.
- Identifying metadata: observed 2026-08-26 in activation/case/run/attempt/command/Frame set recorded by `P0-20260826-PARALLEL-QUALITY-PROJECTION-ORDER-REJECTED`; Dialogue first substantive event was approximately 4.958 seconds after submit and the duplicate-slot failure occurred approximately 6.077 seconds after submit.

## P0-20260826-PARALLEL-PARTIAL-COMPLETION-ORPHANED

- Severity: P0
- Status: FIXED / FOCUSED_CHECK_PASSED / UAT_PENDING
- Component: Target E2E Intake V4 partial-failure convergence
- Confirmed fact: When one or two parallel Intake lanes have a current sealed result and another lane terminates without a result, the production completion query returns only the result-bearing rows and `findExactThreeCompletion()` raises `INTAKE_PARALLEL_COMPLETION_INCOMPLETE`.
- Root cause and evidence: The completion query in `JdbcIntakeParallelFrameStagingStore` uses an inner join to the current result, while the incomplete-cardinality error is a `StagingConflictException` extending `IllegalStateException`. `HttpTargetE2EIntakeParallelFrameExecutionClient` performs `prepare()` outside its typed terminal-failure convergence boundary and rethrows runtime failures from EOF `finish()` unchanged; only `TargetE2EGraphClientException` with `FAIL_LOGICAL_RUN` invokes `terminalizeUncommittedFailure()`. The mixed sealed/failed state therefore bypasses `failUncommitted()`.
- Impact: The Frame set can remain `COLLECTING` with mixed `SEALED` and `FAILED` slots after the AgentRun has failed. The state cannot publish READY, FINAL, RESULT_READY, or formal business writes, but nonretryable or exhausted replay can encounter the same untyped conflict and remain orphaned instead of converging to `FAILED_UNCOMMITTED`.
- Identifying metadata: confirmed by reverse static audit on 2026-08-26 at candidate `3b8ca8df958b895c1b0b36c09a960e00b3789903`; affected anchors include `HttpTargetE2EIntakeParallelFrameExecutionClient.java:141,188,207,562`, `JdbcIntakeParallelFrameStagingStore.java:364,717,888`, and `IntakeParallelFrameStagingPort.java:766`; no incorrect READY, duplicate FINAL, RESULT_READY, or formal commit path was found.

## P0-20260826-PARALLEL-EXECUTE-TRANSPORT-PROTOCOL-REJECTED

- Severity: P0
- Status: OPEN / ROOT_MECHANISM_FIXED / UAT_PENDING
- Component: Target E2E Intake V4 multiplexed EXECUTE stream consumption
- Confirmed fact: On fresh activation `p9act.v1.172f8047688af02f532d7c400be18a55`, the first USER ROOM_MESSAGE of fresh case `CASE_P9_6A8DD149_1` durably accepted all three generation-1 Frame starts and two DIALOGUE projections, then emitted public V4 error `TARGET_E2E_GRAPH_PROTOCOL_REJECTED` at approximately 7.790 seconds without accepting any `FRAME_INTERRUPTED`, `FRAME_GENERATION_RESET`, `FRAME_SEALED`, usage, or FINAL event.
- Root cause and evidence: The rejection is inside Java `HttpTargetE2EIntakeParallelFrameExecutionClient.executeOrResume -> transport.stream/StreamSession`, on the sixth complete technical Frame event before its staging append. The five accepted technical NDJSON lines reconstruct byte-for-byte to 4,017 response bytes, while the mTLS proxy recorded 4,862 response-body bytes, proving that one additional newline-terminated event of 845 bytes was emitted by Python and reached the Java response boundary. A natural Python EOF is excluded: `_execute_live` cannot close normally before its runner terminates, all three Provider calls completed later, all three child checkpoints remained inside `invoke_model`, and the Graph attempt stayed `EXECUTING`. The retained public ledger does not preserve the private sixth event or the exact Java predicate that rejected it. Java subsequently marked the Frame set `FAILED_UNCOMMITTED` with all three slots still generation-1 `STARTED` and no current result.
- Regression root-cause fact: Fresh activation `p9act.v1.1724c4fe5fc2bd01cf5ff979a66bd7a3` closed the previously unavailable predicate. After three starts and ten accepted projections, `QUALITY_FRAME` reached a complete child checkpoint, but the first seal path persisted neither usage nor seal and the attempt high-watermark remained sequence `12`. `HttpTargetE2EIntakeParallelFrameExecutionClient.acceptSealed()` first emits `AgentStreamEventV4.Payload.usagePayload`, whose contract deliberately carries `frameType` and `generation` but no `frameId`; `JdbcIntakeParallelFrameStagingStore.requireCurrentFrameAuthority()` nevertheless derives the current frame identity from `payload.frameId()` or `payload.newFrameId()` for every ingress kind. Both are null for `USAGE`, so every first Frame seal deterministically fails as `INTAKE_PARALLEL_FRAME_ID_DRIFT` before usage or result persistence, independent of Provider output or lane completion order.
- Impact: The first substantive Intake turn cannot complete any independent Frame, exact-three assembly, FINAL, RESULT_READY, or formal business write. The rejected Java transport closes the active response while all three Python child generations are still nonterminal, so their native per-lane repair path cannot complete.
- Identifying metadata: observed 2026-08-26; candidate `216041dbada7caffc38453435f4f1d38e0093803`; case `CASE_P9_6A8DD149_1`; message `MESSAGE_33c29a148ec847c9a4bc221003225f1d`; run `target-intake-run:5076a5bd178b31ff80d37d93f230fa44`; attempt `target-intake-attempt:5076a5bd178b31ff80d37d93f230fa44:1`; command `intake-message:5076a5bd178b31ff80d37d93f230fa44`; Frame set `IFS_2523f935af079424b8de26bb60ef4ef3`; no replay or second case was created.
- Verification fact: `JdbcIntakeParallelFrameStagingStoreContractTest` passed 8/8, including the frame-type/generation authority positive case and cross-lane negative case for a `USAGE` payload without `frameId`; `HttpTargetE2EIntakeParallelFrameExecutionClientTest` passed 8/8. Runtime UAT remains pending.

## P0-20260826-PARALLEL-PROJECTION-IDENTITY-NAMESPACE-CONFLICT

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 per-Frame projection staging and generation retry
- Confirmed fact: Each of the three independent Provider Frame contracts requires `provider_slot_id` to be unique only inside that Frame output. The technical staging schema instead enforces `unique (frame_set_id, canonical_item_id)` across all three Frame types and every generation in the Frame set.
- Root cause and evidence: Python carries the Provider-local `provider_slot_id` unchanged into `CanonicalPublicProjectionItem.canonical_item_id`; neither the Pydantic output contracts nor the three Frame prompts establish a shared cross-Frame namespace. `V081__intake_parallel_frame_staging.sql` then applies one Frame-set-wide uniqueness constraint. The same constraint also rejects a valid generation-2 single-lane retry that deterministically re-emits the generation-1 slot identifiers, even though the staging primary key and all local-index authority are generation-scoped.
- Impact: A valid first-attempt interleaving can be rejected when two independent Frames select the same local slot identifier, and a valid single-Frame generation retry can be rejected when it reuses its own stable slot identifiers. Either case prevents the affected lane from sealing and blocks exact-three assembly despite structurally valid Provider output.
- Identifying metadata: confirmed by static contract comparison on 2026-08-26 at candidate `216041dbada7caffc38453435f4f1d38e0093803`; affected anchors include `parallel_outputs.py:54-62,101-107,180-196`, `parallel_graph.py:1008-1051`, the three `intake_turn_*_frame.md` prompts, and `V081__intake_parallel_frame_staging.sql:384-420`.

## P0-20260826-PARALLEL-JSON-SCALAR-CANONICALIZATION-REJECTED

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Java RFC 8785 consumer for Intake V4 `JSON_VALUE` projections
- Confirmed fact: The Python Dossier and Quality Frames legitimately emit `JSON_VALUE` projections whose canonical values are JSON strings and numbers. A focused interleaved Java consumer test accepts the Dialogue TEXT projection, then fails when the Quality Frame supplies the first numeric JSON value.
- Root cause and evidence: `TargetE2EIntakeParallelTransportCodec` and `JdbcIntakeParallelFrameStagingStore` call `ContractJson.canonicalString` on the projection value itself. `ContractJson` passes the root JSON text directly to `org.erdtman.jcs.JsonCanonicalizer`, whose implementation rejects a primitive root instead of canonicalizing it. Quality scores are primitive numbers by contract, while Dossier summaries are primitive strings, so both legal Frame output shapes cross this unsupported root-value boundary before they can be durably staged.
- Impact: A valid three-lane first attempt cannot reliably pass the first Dossier or Quality public projection. The lane fails before seal, exact-three READY, FINAL, RESULT_READY, and formal Intake commit; Provider regeneration cannot repair the deterministic Java consumer mismatch.
- Identifying metadata: confirmed on 2026-08-26 by `HttpTargetE2EIntakeParallelFrameExecutionClientTest` after replacing TEXT-only fixtures with the real Dialogue TEXT, Dossier JSON string, and Quality JSON number projection shapes; candidate base `7b097e8cba0ed969f47b9d96692e8483ef8f9633`.

## P0-20260826-PARALLEL-QUALITY-PROJECTION-DIMENSION-MAX-DRIFT

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 `QUALITY_FRAME` public projection and dimension-specific score registry
- Confirmed fact: On fresh activation `p9act.v1.7471ad703b4ca92bd1a84fd64d12f4bf`, the only substantive USER `ROOM_MESSAGE` in fresh case `CASE_P9_6A8DE4E9_1` started all three generation-1 lanes and durably accepted the first Quality projection at path `intake.quality.scores.references` with numeric value `18`. The frozen `REFERENCES` maximum is `15`, so the frontend rejected it approximately 101 ms later as `并行接待流投影项不符合冻结注册表`.
- Root cause and evidence: `QualityPublicMetricProposalV1.candidate_score` exposes one generic Provider-visible range of `0..20`, while the sealed `IntakeQualityScoresV1` and frontend registry enforce dimension-specific maxima (`REFERENCES=15`, `EVENT_STORY=20`, `PARTY_POSITIONS=20`, and the remaining dimensions `15`). The partial public item is streamed before the complete Quality object can enforce equality with its dimension-specific sealed score, allowing `REFERENCES=18` to escape as preview. The projection table stores JSON number `18`; the V4 event stores the canonical JSON text in its string-valued `canonical_value_json` envelope exactly as designed, so scalar type conversion is not the failure.
- Impact: A Provider value accepted by the public-item wire Schema can be rejected by the frozen dimension registry after it is already visible. The first Quality preview fails before any lane seals, so the exact-three turn cannot reach assembly READY, FINAL, RESULT_READY, or the single formal Intake write.
- Identifying metadata: observed 2026-08-26; candidate `49d872fe5d47faa9a1f010ebb414c723447cad53`; case `CASE_P9_6A8DE4E9_1`; run `target-intake-run:fe5c2c08ec1d3d6e916c4a5568cac47e`; attempt `target-intake-attempt:fe5c2c08ec1d3d6e916c4a5568cac47e:1`; command `intake-message:fe5c2c08ec1d3d6e916c4a5568cac47e`; Frame set `IFS_f31bc798af5ea8760fea5aec6cef133e`; Quality first projection approximately 6.045 seconds after browser submit.

## P0-20260826-PARALLEL-DIALOGUE-FIRST-GENERATION-SCHEMA-INVALID

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 `DIALOGUE_FRAME` first-generation Provider output
- Confirmed fact: In the same single-turn UAT, Dialogue generation 1 durably emitted its start and first public projection, then terminated as `OUTPUT_SCHEMA_INVALID` with validation path `$`, `retryable=true`. The runtime immediately admitted Dialogue generation 2, increasing the Graph attempt Provider call count from the expected three to four.
- Root cause and evidence: The Provider-visible JSON Schema constrains `candidate_text` length but does not expose the local after-validator that forbids `?` and `？`. With two authorized question slots in an `ASK_SUBSTANTIVE` turn, the Provider produced a valid acknowledgement at item `0` and two later items that each failed with item-level `value_error`. That exact location/type uniquely selects the question-mark after-validator: length, Literal, and extra-field failures have distinct Pydantic types, while projection-slot reconciliation fails at the root. The full 1,152-character Provider response was not retained, but Python logged both exact item locations and the V4 ledger proves only item `0` was published before interruption/reset.
- Recurrence fact: On fresh activation `p9act.v1.9cd9a8bb2c8adcc8752e9cb08aaf4685` at candidate `e34e2f750af48276c6d7b4647a047e9842f59874`, the first USER `ROOM_MESSAGE` of fresh case `CASE_P9_6A8E2926_1` again emitted two accepted Dialogue projections and then interrupted generation 1 as `OUTPUT_SCHEMA_INVALID` before `USAGE` or `FRAME_SEALED`; generation 2 was admitted and aggregate Provider calls reached four. The retained runtime evidence does not contain the rejected Provider JSON or the inner Pydantic location/type/message, so it does not prove that the recurrence is the same question-mark predicate; it only fixes the failure boundary after two valid projections and before complete-document sealing.
- Impact: The three-lane first-attempt acceptance requirement is violated independently of the Quality projection type drift. Retrying only Dialogue may provide resilience, but cannot make this UAT a first-generation success or authorize exact-three assembly for the observed turn.
- Identifying metadata: same activation/case/run/attempt/command/Frame set as `P0-20260826-PARALLEL-QUALITY-PROJECTION-DIMENSION-MAX-DRIFT`; Dialogue first projection approximately 5.358 seconds, generation-1 interruption approximately 10.178 seconds, and generation reset approximately 10.247 seconds after browser submit; final AgentRun state `ABORTED/UNCOMMITTED` with no formal Agent reply or dossier revision.
- Recurrence metadata: observed 2026-08-26; run `target-intake-run:68090149a99e30fb833e0678b0f6ee75`; attempt `target-intake-attempt:68090149a99e30fb833e0678b0f6ee75:1`; command `intake-message:68090149a99e30fb833e0678b0f6ee75`; thread `grt.v1.01a03b5aeedc7f908e3e4edf09ad34d7`; Frame set `IFS_31e5011ab1c0f9a3dda91b650ef9bcf1`; browser TTFT `4,488.7 ms`; generation-1 interruption `14,427.4 ms`; public terminal error `TARGET_E2E_GRAPH_TRANSPORT_FAILED`; no Frame result, assembly, FINAL, RESULT_READY, formal reply, dossier revision, manifest, or terminal receipt was written.
- Focused verification fact: Dialogue V2 removes the Provider-authored terminal slot trace, action binding, phase hash, and language echo; the durable item trace is now the sole slot authority. A two-item first generation sealed with one Provider call and no terminal slot echo, while duplicate item identity still produced bounded lane-local repair/fail-closed behavior. The focused Python and Java selectors passed 37/37; fresh runtime UAT remains pending.

## BUG-20260826-PARALLEL-DIALOGUE-REMARK-ACTION-AUTHORITY-UNBOUND

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 Dialogue action authority
- Confirmed fact: `NOT_READY` and `READY_PENDING_REMARK_INVITE` determine the current visible action from the persisted phase, but `WAITING_FOR_REMARK` requires a distinction between `ACK_REMARK` and `ACK_NO_REMARK` that the immutable Java assembly context does not currently carry.
- Root cause and evidence: Python's parallel context maps `WAITING_FOR_REMARK` to `ACK_REMARK`, while Java's `IntakeParallelFrameAssembler.currentAction(...)` instead reads the Provider-authored `dialogue.action_binding.action` and permits either acknowledgement. `IntakeParallelAssemblyContextResolver.TrustedTurnContext` and the assembler `AssemblyCommand` contain the previous dossier and current message but no independently authenticated remark disposition or current-action authority.
- Impact: Removing the Provider-authored action binding without replacing its one legitimate remark-disposition input would deterministically classify a no-extra-remark turn as a remark turn, persist `HAS_REMARKS` instead of `NO_EXTRA_REMARKS`, and change the existing `IntakeTurnProposal` action and handoff state.
- Identifying metadata: confirmed by read-only reverse consumer audit on 2026-08-26 at candidate `e34e2f750af48276c6d7b4647a047e9842f59874`; affected anchors include `intake_parallel_context.py:336-347`, `IntakeParallelFrameAssembler.java:835-846`, `IntakeParallelAssemblyContextResolver.java:24-30`, and `TargetE2EIntakeParallelAssemblyCoordinator.java:256-300`; no runtime or source mutation was used to establish the defect.
- Focused verification fact: Dialogue V2 retains only a request-bound `remark_disposition` semantic: it is fixed to `null` outside `WAITING_FOR_REMARK` and limited to `REMARK/NO_REMARK` inside that phase. Java maps those two values to the existing `ACK_REMARK/HAS_REMARKS` and `ACK_NO_REMARK/NO_EXTRA_REMARKS` branches while deriving all other current actions from persisted phase authority. Both acknowledgement branches and the non-waiting negative gate passed in the 37-test focused selector.

## P0-20260826-PARALLEL-DOSSIER-FIRST-GENERATION-CROSS-FIELD-DRIFT

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 `DOSSIER_FRAME` first-generation Provider output
- Confirmed fact: On fresh activation `p9act.v1.45e4a121551ba05ecbc8c23eec63cee9`, the only substantive USER `ROOM_MESSAGE` in fresh case `CASE_P9_6A8DEA8E_1` started all three generation-1 lanes. Dossier generation 1 durably emitted one valid `CURRENT_FACT` projection, then terminated at the complete-document boundary with `OUTPUT_SCHEMA_INVALID`, validation path `$`, and no sealed result. The runtime admitted Dossier generation 2, so the turn failed the first-generation acceptance gate and was frozen without a formal Agent reply or dossier revision.
- Root cause and evidence: The Provider-visible Dossier schema represents the same current-source fact independently in `public_projection_items[*].source_row`, `public_projection_items[*].candidate_value`, and `dossier_delta.matrix_patch.fact_rows[*]`; it also repeats every item identity in `dossier_delta.public_projection_slots`. The Provider JSON Schema validates each field locally but cannot express the terminal root equalities enforced by `IntakeDossierFrameV1.validate_projection_trace`: exact ordered item-slot equality and exact ordered public-row-to-matrix-row equality. The accepted first item proves its item-local type and current-source checks passed, while the root-only failure proves one of those duplicated authorities drifted after preview. The rejected complete Provider document was not retained, so the two root sub-branches cannot be distinguished more narrowly from persisted evidence.
- Impact: A Provider-schema-valid Dossier prefix can become externally visible and then fail only when independently generated copies are reconciled. The affected lane cannot seal on its first generation, preventing exact-three assembly READY, FINAL, RESULT_READY, and the single formal Intake write even when the other two lanes are independent.
- Focused verification fact: Dossier V2 now exposes one current-source fact representation and derives its matrix/summary projections deterministically. The request-bound Python contract and semantic checks passed 58 focused tests; the downstream Java assembly/freezer selectors passed 35 focused tests with no failure, error, or skip.
- Identifying metadata: observed 2026-08-26; candidate `3fffbd415fec9daa0c7ffa8c7c6474551125fa1d`; case `CASE_P9_6A8DEA8E_1`; run `target-intake-run:e265437a1c7f33bba39a08cd985cafa5`; attempt `target-intake-attempt:e265437a1c7f33bba39a08cd985cafa5:1`; command `intake-message:e265437a1c7f33bba39a08cd985cafa5`; Frame set `IFS_a8d74772fde47a95549ad0a3804ac17c`; Dossier start approximately 1.568 seconds, first projection approximately 7.290 seconds, and generation-1 interruption approximately 14.990 seconds after browser submit.

## BUG-20260826-PARALLEL-DOSSIER-RESPONDENT-CAPACITY-UNBOUND

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 `DOSSIER_FRAME` respondent-claim authority
- Confirmed fact: `DossierFrameDeltaV2.respondent_claim` is currently accepted independently of the authenticated actor's initiator/respondent capacity. Python copies a non-null value into the materialized `CaseFactMatrixDeltaV2`, and the Java assembler copies the same value into the formal proposal without a capacity check.
- Root cause and evidence: The Provider-facing schema contains only the claim payload, while the request-bound Python semantic validator is bound only to `actor_role`; the Java `dossier(...)`/`materializeMatrixPatch(...)` path receives no trusted capacity discriminator. The Prompt asks a non-respondent to emit `null`, but no machine boundary enforces that instruction.
- Impact: A schema-valid first-generation output from the initiator can author the respondent partition and reach an otherwise valid exact-three proposal, violating current-actor-only matrix authority even when no retry or transport failure occurs.
- Focused verification fact: The request-bound Python output type fixes `respondent_claim` to `null` for an initiator, and Java revalidates respondent capacity before formal proposal construction. Cross-role negative tests passed in the 58-test Python and 35-test Java focused selectors.
- Identifying metadata: observed by read-only Dossier V2 pre-activation review on 2026-08-26; affected files `parallel_outputs.py`, `parallel_graph.py`, and `IntakeParallelFrameAssembler.java`; no runtime mutation.

## BUG-20260826-PARALLEL-DOSSIER-FACT-KEY-AUTHORITY-UNBOUND

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 `DOSSIER_FRAME` fact-key authority
- Confirmed fact: Dossier V2 currently validates a Provider fact key only as a syntactically valid `FACT_`/`NEW_` key and as locally unique. Neither Python materialization nor Java assembly proves that an existing `FACT_` key belongs to the frozen matrix or that a `NEW_` key belongs to the server-issued namespace for this command.
- Root cause and evidence: The single-source-row schema carries no request-bound allowed-key proof, the Python semantic validator does not compare rows with a trusted key authority, and the Java `AssemblyCommand`/assembler has no allowed-existing-key set or issued-new-key namespace to revalidate before constructing the formal matrix patch.
- Impact: A schema-valid first-generation output can invent or overwrite a fact identity and still seal, assemble, and enter the formal proposal path, corrupting frozen matrix lineage and replay identity without triggering the retry mechanism.
- Focused verification fact: The model context now carries the exact frozen `FACT_` set and a command-bound `NEW_` namespace; the Provider-visible schema narrows both key classes and Java revalidates membership, namespace, stable metadata, and source scope. Unknown-existing and foreign-new negative tests passed.
- Identifying metadata: observed by read-only Dossier V2 pre-activation review on 2026-08-26; affected files `parallel_contracts.py`, `parallel_outputs.py`, `parallel_graph.py`, and `IntakeParallelFrameAssembler.java`; no runtime mutation.

## P0-20260826-PARALLEL-DOSSIER-CURRENT-DELTA-OMITS-FORMAL-PARENT-CARRY

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 Dossier V2 to Java formal matrix bridge
- Confirmed fact: Dossier V2 intentionally emits only current-source rows, and the Java assembler currently derives `case_fact_matrix.delta.v2.fact_rows` only from those rows. Both `IntakeInitiatorMatrixDeltaFreezer` and `IntakeRespondentMatrixFreezer` require every prior formal fact to be carried exactly once in a successor delta.
- Root cause and evidence: Removing Provider-owned previous rows eliminated duplicated model authority, but the deterministic Java assembler did not replace that removed responsibility. Its `materializeMatrixPatch(...)` builds rows exclusively from `public_projection_items`, while both formal freezers compare carried parent IDs with the full parent fact-ID set and reject any omission.
- Impact: A schema-valid, first-generation exact-three turn can reach assembly READY but fail the single formal Intake transaction whenever the frozen matrix contains a prior fact not repeated as a current-source Dossier item. The failure is deterministic and cannot be repaired by retrying the model lane.
- Focused verification fact: Java assembly now reconstructs the complete successor delta in formal parent order, deep-carries bilateral historical rows, preserves grounded historical respondent claims without rebinding them to the current message, and keeps first-respondent derivation from an initiator-frozen parent. The three Java selectors passed 35/35, and a separate read-only authority review found no blocker.
- Identifying metadata: observed during pre-activation reverse review on 2026-08-26; affected files `IntakeParallelFrameAssembler.java`, `IntakeInitiatorMatrixDeltaFreezer.java`, and `IntakeRespondentMatrixFreezer.java`; no runtime mutation.

## P0-20260826-PARALLEL-FRESH-UAT-UNCLASSIFIED-BEFORE-SEAL

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / RUNTIME_VERIFIED
- Component: Target E2E Intake V4 parallel Frame execution and typed failure boundary
- Confirmed fact: On fresh activation `p9act.v1.d36da1502d481270860dca76f33f4cf1` bound to candidate `612ca2df4219bb13f6c447bb5830353b655a5391`, the first and only USER substantive message of fresh case `CASE_P9_6A8E0898_1` failed about 23.271 seconds after submit with public code `INTAKE_PARALLEL_EXECUTION_UNCLASSIFIED` and `retryable=false`.
- Runtime evidence: All three generation-1 Frame lanes started. QUALITY and DOSSIER emitted live public projections, but no lane had produced a durable `FRAME_SEALED` before the public terminal error. The UAT was frozen at that first error; no retry, second case, Evidence transition, or downstream write was initiated.
- Root cause evidence: `JdbcIntakeParallelFrameStagingStore.LOCK_FRAME_SQL` did not select `slot.frame_type`, while `requireCurrentFrameAuthority(...)` reads the locked row's `frame_type` for every `USAGE` ingress. Start and projection ingress did not exercise that field, so they committed successfully; the first completed QUALITY Frame deterministically failed before its USAGE event could commit with `StagingConflictException` code `INTAKE_PARALLEL_CORRUPT_AUTHORITY` and message `frame_type is not text`. A rollback-only reproduction using the retained run, attempt, Frame set, and exact QUALITY usage reached the production `append(...)` path and produced that same Java authority-decoding failure; run/attempt status, sequence 13, zero USAGE ingress, and COLLECTING state remained unchanged afterward.
- Contributing failure-classification evidence: The parallel gateway created a new in-memory `ProgressTracker` at `lastSequenceNo=-1/publicOutputEmitted=false` for every Temporal Activity invocation and did not seed or refresh it from the persisted V4 attempt. Frame ingress commits the V4 event and monotonic attempt progress before invoking that tracker. The first Activity for this run started with persisted `public_output_emitted=false`; later retries started with persisted `public_output_emitted=true`, while each new gateway tracker still began empty. A commit-to-callback failure window or Activity replay could therefore classify a failure after durable public output as `INTAKE_PARALLEL_EXECUTION_UNCLASSIFIED` before the retry budget later froze it as non-retryable.
- Focused verification: After selecting `slot.frame_type` into the locked Frame authority, the retained exact QUALITY usage crossed the full production `JdbcIntakeParallelFrameStagingStore.append(...)` path and returned sequence/high-watermark `14/14` in a rollback-only transaction. The permanent staging contract test and rollback reproduction passed twice (10/10 each), `git diff --check` passed, and the retained run remained ABORTED at sequence 13 with zero USAGE ingress and COLLECTING assembly state.
- Exact runtime identity: run `target-intake-run:9d3e23da52753dcc9998c3b92f7f68f9`; attempt `target-intake-attempt:9d3e23da52753dcc9998c3b92f7f68f9:1`; command `intake-message:9d3e23da52753dcc9998c3b92f7f68f9`; frame set `IFS_a2998221f1243d90fdc555a307bded54`; thread `grt.v1.01a03adb322070ca84b0a8f0b3a741f9`. QUALITY reached child checkpoint `1f1a0cd8-f6a7-6577-8003-934bd3bccfed` `COMPLETE`; all three Java slots remained `STARTED`; the durable V4 ledger contained three starts, ten projections and one error, with zero usage, seal, interruption or final events.
- Runtime verification: Fresh activation `p9act.v1.5535827963fa0fcaf136b79ced00c032` bound to candidate `356f98d300151804b508a3c6a60f3da2c0de6c6e` durably accepted QUALITY generation-1 `USAGE` and `FRAME_SEALED`, created its immutable Frame result, and advanced the V4 ledger through sequences 12 and 13. This proves the missing locked `frame_type` authority no longer blocks the first completed lane in runtime.
- Impact: The previously affected first-seal boundary is restored. The fresh UAT then encountered a distinct downstream public-stream/lane interruption failure recorded separately below.
- Identifying metadata: observed 2026-08-26; activation `p9act.v1.d36da1502d481270860dca76f33f4cf1`; candidate `612ca2df4219bb13f6c447bb5830353b655a5391`; case `CASE_P9_6A8E0898_1`.

## P0-20260826-PARALLEL-V4-USAGE-PUBLIC-REJECTS-WHILE-LANES-INTERRUPT

- Severity: P0
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 public stream projection and independent Frame lane completion
- Confirmed fact: On fresh activation `p9act.v1.5535827963fa0fcaf136b79ced00c032` bound to candidate `356f98d300151804b508a3c6a60f3da2c0de6c6e`, the only USER substantive message of fresh case `CASE_P9_6A8E195B_1` returned HTTP 201, then the page entered Intake error about 16.235 seconds after submit with code `AGENT_STREAM_V4_USAGE_INVALID` and message `并行接待流 usage 无效`.
- Runtime evidence: All three generation-1 lanes started at about 1.85 seconds. DOSSIER emitted its first public projection at about 5.725 seconds and QUALITY at about 6.589 seconds. QUALITY generation 1 completed one Provider call, persisted one `USAGE`, one `FRAME_SEALED`, and one immutable result at about 16.093 seconds. DIALOGUE generations 1 and 2 were both interrupted, and DOSSIER generation 1 was interrupted while generation 2 remained STARTED at evidence capture. Aggregate Provider calls reached five; the first retained backend lane failure is `GRAPH_PROVIDER_STREAM_INTERRUPTED` on DIALOGUE generation 1. The retained Python logs do not expose a more specific inner exception.
- Public-stream root cause: The Java V4 staging authority deliberately accepts `USAGE` only while the selected generation and slot are `STARTED`, then atomically seals the immutable result; the durable replay order is therefore `USAGE` followed by `PUBLIC_FRAME_SEALED`. The frontend reducer instead required the selected Frame to be `SEALED` before accepting `USAGE`, even though it sets that status only when processing the following seal event. All usage fields, token values, delivery class, Frame type, and generation were valid; the contradictory state predicate alone produced `AGENT_STREAM_V4_USAGE_INVALID`.
- Lane root cause: The retained LiteLLM accounting shows DIALOGUE generation 1 and DOSSIER generations 1 and 2 each consumed the exact 16,384-token output ceiling, while the successful QUALITY generation used 857 output tokens. DIALOGUE generation 2 is additionally aligned to an upstream OpenAI/Qwen API error stating that structured JSON generation became abnormal and was aborted because the partial response might be incomplete or invalid. The model gateway maps `finish_reason=length` into `AgentServiceUnavailable`, then into a transient `ModelStreamInterrupted`, so a deterministic output-limit/schema-incomplete termination receives lane-retry authority and repeats Provider work.
- Formal-write evidence: The Frame set remains `COLLECTING`; READY, durable FINAL, RESULT_READY, proposal artifact, graph-result artifact, terminal receipt, manifest, formal domain operation, Agent reply, and new dossier/matrix revision are all zero. The AgentRun remains RUNNING/UNCOMMITTED and the Graph command remains EXECUTING.
- Focused verification: The V4 frontend reducer accepted the durable `USAGE -> PUBLIC_FRAME_SEALED` order, committed per-lane usage only after the matching seal, aggregated the exact three lanes to `300/60/360`, and preserved a reset Dossier lane without contaminating its replacement or sibling lanes. The three Frame request budgets are now `1024/4096/2048`; the Provider-visible Dialogue/Dossier contracts expose `2/6` item limits and bounded text fields; a `finish_reason=length` now surfaces as `AgentOutputSchemaError` with `AGENT_OUTPUT_TOKEN_LIMIT_EXCEEDED`. The selected Python tests passed 5/5 and `IntakeParallelFrameAssemblerTest` passed 17/17.
- Impact: A first-generation lane can seal successfully while the public consumer rejects its V4 usage event and sibling lanes enter generation interruption/retry. The three-lane hard gate cannot produce exact-three READY or a single formal Intake turn, and the aggregate Provider call count rises above the intended three.
- Exact runtime identity: run `target-intake-run:96adbc384e09323faf4d7425bd76ab95`; attempt `target-intake-attempt:96adbc384e09323faf4d7425bd76ab95:1`; command `intake-message:96adbc384e09323faf4d7425bd76ab95`; frame set `IFS_42e2b2a0f3827d2edebd5bd03aea639a`.
- Identifying metadata: observed 2026-08-26; activation `p9act.v1.5535827963fa0fcaf136b79ced00c032`; candidate `356f98d300151804b508a3c6a60f3da2c0de6c6e`; case `CASE_P9_6A8E195B_1`; UAT frozen after the first error with no resend, second case, cleanup, or downstream transition.

## BUG-20260826-PARALLEL-ADMISSION-REPLAY-LOSES-CURRENT-GENERATION

- Severity: P1
- Status: FIXED / FOCUSED_VERIFIED / UAT_PENDING
- Component: Intake V4 Frame-set replay and immutable admission-receipt publication
- Confirmed fact: `JdbcIntakeParallelFrameStagingStore.admit(...)` returns `FrameSetReceipt.selectedGenerations` as generation 1 for all three lanes both on first insert and on an exact existing Frame-set replay. After `planExecution(...)` advances one retryable failed lane to generation 2, `publishAdmissionReceipt(...)` requires the receipt-selected generation to equal both the execution plan and the locked current slot generation.
- Root cause and evidence: The replay path validates the existing admission but does not read its three current slot generations before constructing the `FrameSetReceipt`. The fixed generation-1 map at `JdbcIntakeParallelFrameStagingStore.java:560-569` conflicts with the exact equality gate at `JdbcIntakeParallelFrameStagingStore.java:2276-2309` whenever a legal lane-only retry has advanced one slot.
- Impact: A same-command Activity replay after a retryable single-lane failure can plan the correct generation-2 replacement but cannot publish the immutable admission receipt required for Graph EXECUTE or later TERMINATE. The lane-only retry stalls before Provider execution even though sibling sealed lanes and the replacement slot remain valid.
- Identifying metadata: confirmed by read-only pre-commit authority audit on 2026-08-26 against candidate HEAD `f9f670c5`; no service, database, browser, Temporal, or Provider mutation was used.
- Focused verification update: The duplicated generation map was removed from the admission receipt authority. Receipt publication now validates the execution plan against the locked current Frame slots/results, so an exact same-command replay can select a legal generation-2 replacement without contradicting an immutable generation-1 receipt. The focused staging, HTTP client, and gateway suites passed 28/28 after this change; runtime UAT remains pending.
