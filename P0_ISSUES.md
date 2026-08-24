# P0 / Confirmed Bug Ledger

## P1-20260824-EVIDENCE-IMAGE-GRAPH-CONTRACT-REJECTED-6A8AC2C9-13

- Severity: P1
- Status: FIXED_FOCUSED_VERIFIED
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
- Status: FIXED_FOCUSED_AND_UAT_VERIFIED
- Component: Hearing answer-bundle concurrent process-revision handling
- Confirmed fact: Case `CASE_P9_6A88011D_1` accepted and applied the USER V4 answer bundle, while the immediately following MERCHANT answer first returned retryable HTTP 409 `expected process revision is already reserved by an active command` and then returned HTTP 500 on the retry; no MERCHANT answer action or command was persisted.
- Root cause and evidence: The retry overlapped the USER answer command's optimistic state update. Java API logged `ObjectOptimisticLockingFailureException` for the request at 15:53:24.459, but the global exception boundary returned generic `INTERNAL_ERROR` instead of the existing fail-closed revision-conflict response. The USER command later reached `APPLIED`, and the durable action ledger contains only the USER `ANSWER_BUNDLE`.
- Impact: The functional UAT is interrupted at `PARTY_ANSWERS_OPEN` with USER submitted and MERCHANT pending; the same case remains resumable after the predecessor command settles.
- Identifying metadata: observed 2026-08-21; request `REQ_d217fefe768b464b9b814d75d1243fb7`; trace `TRACE_f46f38754fef90033040c1b79d9986f5`; USER command `hearing-action-HEARING_ACTION_2780188077664957ba7395e1d953f2c2`; failed stage `hearing_merchant_answer_bundle/status`.

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
- Status: FIX_IN_PROGRESS
- Component: Initiator Intake public question generation
- Confirmed fact: Fresh canonical cases `CASE_P9_6A88B179_3`, `CASE_P9_6A88B179_4`, and `CASE_P9_6A88B179_5` all asked the USER for the merchant's attitude or handling plan. Case `_5` completed without a backend error but remained `INCOMPLETE` at score `81`; its persisted `nice_to_have_gaps`, `next_questions`, admission reasoning, and handoff instruction treated the absent merchant report as unfinished USER Intake information.
- Root cause and evidence: The exact assembled system message contained the base instruction that the initiator must not supply opponent attitude, then appended the USER profile instruction to ask for “用户所了解的商家态度” as the last role-specific rule. The fresh domain snapshot contained no prior dossier or merchant position, and the strict initiator Schema exposed no `merchant_claim`, `respondent_position`, or `respondent_attitude` field. The provider therefore returned a structurally valid attributed-question path while the contradictory prompt made the optional report affect completeness.
- Impact: The formal matrix remains structurally actor-local, but a USER who does not report the merchant's attitude can be kept below the Intake readiness threshold even though the merchant's direct position belongs to the later MERCHANT turn.
- Verification evidence: AgentRun `target-intake-run:7bb8346ac2913d2cb61e5168f3723d93` completed on its first attempt with no validation error; its persisted dossier records the merchant-attitude question and `ready_for_next_step=false`. Reconstructed production input contains only `case_identity`, `initial_case_facts`, an empty/default `previous_dispute_outline`, and empty recent dialogue, while the assembled system prompt places the contradictory USER-profile rule after the actor-local base rules.
- Identifying metadata: observed 2026-08-22; activation `p9act.v1.26cda0db48946e3cf9b9799f0ff888f3`; cases `CASE_P9_6A88B179_3`, `CASE_P9_6A88B179_4`, and `CASE_P9_6A88B179_5`; stage `initiator opening`; model `qwen3.7-max`; prompt-attempt count `3/3`.

## BUG-20260822-MERCHANT-INTAKE-SCORE-BREAKDOWN-MISMATCH

- Severity: P2
- Status: FIXED_FOCUSED_VERIFIED
- Component: Intake quality projection
- Confirmed fact: After the first direct MERCHANT statement completed successfully, the persisted dossier reported merchant Intake quality `83`, while the live frontend displayed `完善度 0%` before and after a page reload. In a later USER run, the displayed and persisted total moved from `80` to `9` after a substantive answer even though the six persisted component scores summed to `90`; after the following USER answer, the total remained `9` while the components increased to `94`.
- Root cause and evidence: The earlier accepted `party_intake_state.MERCHANT.intake_quality` stored `score=83`, but its six `score_breakdown` values summed to `88`. In case `CASE_P9_6A892399_2`, command `intake-message:59946793ec803a16b47dda05c6b966d0`, the accepted Python graph proposal itself stored `score=9` while its components were `15 + 18 + 18 + 12 + 12 + 15 = 90`; command `intake-message:018328c4067a38a3b0e44307a5787384` again stored `score=9` with components summing to `94`. The ordered provider schema treats `total_score` and `score_breakdown` as independent bounded fields, the materializer copies `total_score` directly into `intake_quality.score`, and the authoritative-model reducer deliberately preserves that total without recomputing it. Backend structured-output validation therefore accepted all internally inconsistent objects, which then propagated unchanged through the proposal, dossier, turn memory, Java persistence, and frontend.
- Impact: Intake completeness can visibly regress or display a false value despite additional substantive answers, and the displayed progress no longer represents the component assessment carried by the same accepted proposal.
- Verification evidence: Python reducer regression proves a model total of `9` is persisted and projected as the six-component sum `90`; Python proposal validation rejects a party score different from its component sum; the governed live `TURN_EVALUATION` projection rewrites the visible total to `90`; Java formal merge regression proves an incoming score `80` with components totaling `85` is normalized, replayed, and persisted as `85`. All three focused Python tests passed, and the focused Java test passed after full main/test compilation.
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
- Status: CONFIRMED
- Component: Intake failed-turn recovery across Domain event binding and Graph checkpoint state
- Confirmed fact: After the first MERCHANT command in `CASE_P9_6A89E0EC_4` failed after one provider call, a second authenticated MERCHANT message was accepted into the room transcript and admitted as a new command, but the new Graph attempt aborted before any provider call with `INTAKE_EVENT_SEQUENCE_INVALID` and public diagnostic `GRAPH_STREAM_PROTOCOL_REJECTED`.
- Root cause and evidence: `JdbcIntakeGraphBindingStore.allocateEvent` allocates from the maximum private event binding, including the event already bound for a terminally failed Graph command. The failed event therefore retained sequence 2 while the committed Graph checkpoint remained at sequence 1; the next distinct message was immutably bound as sequence 3. Python `_apply_event` requires exact contiguity with the committed checkpoint and rejected 3 because it still expected 2. Graph attempt `target-intake-attempt:15795f8af3183f818659d649aca5700b:1` records `provider_call_count=0`, confirming the failure occurred before model invocation.
- Impact: Once an Intake command fails after its private event is bound, submitting a later participant message on the same private thread cannot resume from the last committed Graph checkpoint; each later allocation remains ahead of the expected sequence.
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
- Confirmed fact: Browser import created fresh case `CASE_P9_6A8AC2C9_6`, but its initial Intake preparation and one explicit retry both ended with the visible message `intake infrastructure preparation is unavailable`; the case remained at Intake and could not enter its room.
- Root cause and evidence: The Java AGENT worker was started while Python was still failing its JWKS bootstrap. Its Graph readiness handshake was rejected and its Spring context exited, while Java API and Python later reported healthy; no AGENT worker remained to execute Intake preparation. Restarting only the AGENT worker after Python became healthy completed the handshake, and the next retry navigated the same case into `/intake` with a live opening turn.
- Impact: The requested post-fix fresh browser UAT cannot start Intake, so Evidence V2 TXT/image verification and all downstream stages remain blocked.
- Identifying metadata: observed 2026-08-23; case `CASE_P9_6A8AC2C9_6`; actor `user-local/USER`; route `/disputes`; activation `p9act.v1.aa7bf7653d871e6558a30de80ce8eb21`.

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
- Status: FIXED / UAT_PENDING
- Component: Target Intake respondent turn structured generation and retry finalization
- Confirmed fact: In fresh user-initiated UAT case `CASE_P9_6A8B9CE8_3`, the MERCHANT second answer produced a complete public reply and updated visible dossier, but both the original generation and its full-regeneration retry failed terminal structured validation; the room ended with `AGENT_OUTPUT_SCHEMA_INVALID` and did not persist the turn.
- Root cause and evidence: Both generations produced internally consistent ready-turn metadata (`total_score=100`, no blocking gaps, `WAITING_FOR_REMARK`, `INVITE_OPTIONAL_REMARK`). Both failed because `CASE_MATRIX.summary_source_fact_keys` referenced fact-key bodies such as `E672...` and `MERCHANT_...` while the same generated matrix rows exposed the authoritative keys as `FACT_E672...` and `NEW_MERCHANT_...`; the strict matrix validator therefore found no matching summary source. The retry log's other branch errors are union-schema alternatives, not additional defects. The frontend reset once and then projected the terminal schema diagnostic after the second identical namespace-prefix rejection.
- Impact: The respondent cannot complete Intake, both parties cannot enter Evidence, and the fresh full-chain run does not count toward the required consecutive UAT successes.
- Regression verification evidence: The focused provider-contract test accepts uniquely resolvable missing `FACT_` / `NEW_` namespace prefixes and still rejects an ambiguous body shared by both namespaces (1 passed). Replaying the exact second rejected generation through the updated respondent Schema now validates and binds all six summary references to keys present in that generation's fact rows.
- Identifying metadata: observed 2026-08-24; case `CASE_P9_6A8B9CE8_3`; actor `merchant-local/MERCHANT`; route `/disputes/CASE_P9_6A8B9CE8_3/intake`; diagnostic `AGENT_OUTPUT_SCHEMA_INVALID`; node `intake_turn_case_detail`; response sizes `6448` and `7953` characters.
