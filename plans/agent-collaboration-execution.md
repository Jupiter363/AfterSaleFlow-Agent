# Adaptive Agent Collaboration Execution Policy

## Purpose and scope

This policy governs sub-agent assignment, model selection, concurrency, review, and verification for repository work. It supplements phase-specific execution plans; where a phase plan imposes a stricter entry gate, runtime constraint, evidence requirement, or path boundary, that plan controls.

The primary agent owns decomposition, path boundaries, integration order, final decisions, and communication. Delegated agents may edit, test, and commit only within the paths in their written brief.

## Provisioned A2A task priority

For this project, the primary must use the following provisioned Codex tasks as the first candidates for every compatible delegated wave:

- `019fcb08-ed40-70e0-9ca7-5ed2bf4e669a`
- `019fcb08-9dbf-7941-8b81-b2adf45f18cf`
- `019fcb07-7690-7c91-b58a-eb3bdebb3135`

The primary continues these tasks through the task/thread messaging interface before spawning a new sub-agent. A task remains eligible even when it is not exposed as a child collaboration target in the current turn. Every assignment still requires exact owned paths, forbidden paths, acceptance criteria, and an explicit handoff format.

A new sub-agent is allowed only when every compatible provisioned task is already running, unavailable, blocked by disjoint path ownership, or insufficient for a required independent lane. The primary records the concrete exception in its user-facing progress update before creating the new agent. Safe disjoint work already in flight may finish; this priority applies to the next assignment wave without destroying partial work.

## Target topology

When the collaboration runtime has capacity, use one primary agent and eleven delegated logical roles:

| Role | Logical slots | Responsibility |
| --- | ---: | --- |
| Implementation owners | 7 | Make disjoint, concrete implementation changes and focused checks. |
| In-flight P0 review lanes | 1 | Independently inspect active high-risk changes before commit or integration. |
| Verification lanes | 2 | Run light test shards and broker up to two isolated Maven/Testcontainers lanes and evidence. |
| Lookahead lane | 1 | Read-only inventory, dependency mapping, next-wave brief preparation, and risk discovery. |

The topology is logical ownership, not a requirement to sample eleven models at once. A constrained runtime must keep all seven implementation ownership domains and schedule them in waves as slots release. Do not collapse owners into an unbounded generalist role merely because the active-agent limit is lower.

## Active-resource limits and launch order

Use the following resource budget unless a stricter runtime or task constraint applies:

- Activate up to all eleven delegated roles when dependencies and host capacity make them useful; do not keep idle roles running merely to fill slots.
- Limit light test execution to two processes.
- Allow at most two Maven/Testcontainers processes, one brokered by each verification role. Concurrent heavy processes must use isolated worktrees, build output, report suffixes, containers, networks, and dynamically allocated ports; otherwise serialize them.
- Keep the primary as the sole mainline integrator. Integration, shared-file resolution, and final evidence assembly are not delegated concurrently.
- Stagger delegated starts by 10-20 seconds, then backfill released slots from the queued logical roles.

The primary starts enough implementation owners to establish progress, then fills the single review lane, verification lanes, and lookahead role without violating the resource budget. Review should inspect a stable patch, worktree, commit candidate, or explicitly shared diff rather than edit the same file as an implementation owner.

## Adaptive model matrix

| Work class | Model and effort | Concurrency rule |
| --- | --- | --- |
| Crypto or security analysis; Temporal race analysis; transactional correctness; final P0 review | `gpt-5.6-sol` `xhigh` | At most 2-3 concurrent assignments. |
| Core implementation with nontrivial domain or control-flow changes | `gpt-5.6-sol` `high` | Fit within the 6-8 sampling budget. |
| Tests, tooling, frontend, migrations, and mechanical integration | `gpt-5.6-terra` `high` | Fit within the 6-8 sampling budget. |
| Read-only inventory, documentation, lightweight triage, and lookahead | `gpt-5.6-terra` `medium` | Use opportunistically; release it first under pressure. |

Do not assign `sol` `xhigh` to routine implementation merely to increase apparent quality; it reduces useful throughput and is reserved for the listed risks. When a requested model or effort level is unavailable, use the nearest available class: prefer the same model at the next lower effort, then the corresponding `terra` or `sol` class appropriate to the work. This fallback never removes required P0 review or lowers a final P0 review below the strongest available reasoning setting.

## Ownership and write safety

Before delegation, the primary records a brief for every role containing all of the following:

- Exact owned paths, preferably file paths; directory ownership is allowed only when its contents are disjoint from every other brief.
- Explicit forbidden paths, including every path assigned to another role, root coordination files unless explicitly assigned, generated evidence, secrets, production configuration, and unrelated working-tree changes.
- Required entry gate, acceptance criteria, focused checks, and whether the role may commit.
- Dependencies, handoff format, and the review lane that will inspect P0-sensitive work.

No two delegated writers may edit the same file concurrently. The primary is the only agent that resolves overlap, changes shared files, or integrates competing commits. Review and lookahead roles are read-only unless the primary later creates a new brief with a separate ownership allocation.

Agents must preserve unrelated changes and must not reset, overwrite, or stage paths outside their brief. They must not cross destructive, secret, production, external-approval, or phase-gated boundaries.

## Review and escalation

P0 includes security, authorization, data integrity, transactionality, Temporal ordering or race behavior, irreversible migration behavior, contractual compatibility, and other defects that can cause production loss or a gate failure.

Keep the single planned P0 review lane active or queued against a stable P0-sensitive candidate while implementation is active. Start review when an owner has a reviewable diff, rather than waiting for all implementation to finish. Reuse the same lane sequentially for independent or final adversarial inspection; do not create concurrent review lanes.

Post-commit review is reserved for P0 concerns. Routine correctness, style, and test feedback must be handled before commit or during integration. A final P0 review uses `sol` `xhigh` when available and examines the integrated candidate, relevant tests, and evidence before promotion past the applicable gate.

Escalate to `sol` `xhigh` when a review uncovers a suspected P0 condition, when a Temporal race or transaction boundary is ambiguous, or when the final P0 gate is reached. Downgrade from `sol` `xhigh` to `sol` `high` after the risk is bounded and the remaining work is ordinary implementation. Downgrade `terra` `high` verification or tooling work to `terra` `medium` only when it becomes read-only inventory or documentation; never downgrade a task merely to bypass a required check.

## Failure and capacity handling

On a 429, 503, timeout, or usage-limit failure:

1. Preserve the role's worktree, diff, notes, and completed focused-check output. Do not discard partial work solely because the assignment failed.
2. Record the failure and hand off the exact owned and forbidden paths to a replacement role. Reassign after a bounded backoff or queue it behind a released slot.
3. Use the nearest available model class from the matrix, retaining the same acceptance criteria and P0 review requirements.
4. Integrate only reviewed, attributable changes. The primary decides whether to continue from the preserved diff or restart that narrow role.

When capacity is below the target topology, retain the seven logical implementation owners, queue the remaining roles, and reuse slots immediately as work completes. Under pressure, defer lookahead first; do not defer required verification, Maven/Testcontainers isolation/serialization, or the single P0 review lane.

## Verification scheduling

Use focused static checks and focused tests while each owner edits. The two light verification lanes may run independent test shards, but never start more than two light test processes together. The two verification owners may each broker one Maven/Testcontainers process and capture attributable evidence. If the heavy shards share a worktree, build directory, fixed port, database, container, or report path, they must run serially instead.

Batch expensive Maven, Testcontainers, cross-service, full regression, end-to-end, and browser verification at the agreed unified checkpoint, unless the user explicitly requests it or a stricter phase gate requires it earlier. Do not run a full regression suite or end-to-end flow after each individual task. The primary records the candidate SHA, commands, results, failures, and rerun decisions needed by the applicable phase plan.

## Integration checklist

Before integration, the primary verifies that each incoming change has an attributable owner, stays inside its declared paths, has focused-check results, and has the required review status. The primary integrates sequentially, resolves shared-file changes alone, schedules batched verification, and opens post-commit review only for P0 findings. At the unified checkpoint, the primary confirms that the final P0 review and required evidence are complete without weakening them for capacity or model availability.
