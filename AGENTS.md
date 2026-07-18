# Local development workflow

- Use an agile implementation loop. Do not run the full regression suite or an end-to-end browser flow after every individual task.
- Run full or end-to-end verification only when the user explicitly asks for it, or once at the agreed unified verification checkpoint.
- Prefer focused static checks while editing; keep expensive cross-service verification grouped at the checkpoint.
- Local debugging uses the frontend on `5173`, the Java dev service on `8080`, and the Python dev service on `18000`. Docker remains the final all-service deployment target.
- Preserve unrelated working-tree changes. Do not reset or overwrite files outside the active task.
- Before Phase 2-8 Temporal refactor work, read `docs/runbooks/temporal-first/phase-1-lessons-quick-reference.md`; open the full retrospective only when detailed evidence or root-cause history is needed.
- Delegated implementation agents may directly create and edit code, tests, migrations, and documentation inside their assigned worktree and owned paths, run focused checks, and commit their work without per-edit user approval.
- The primary agent must define owned and forbidden paths before delegation, integrate sub-agent commits, and prevent sub-agents from staging unrelated working-tree changes or crossing destructive, secret, production, or external-approval boundaries.
