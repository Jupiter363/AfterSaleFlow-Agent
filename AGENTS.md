# Local development workflow

- Use an agile implementation loop. Do not run the full regression suite or an end-to-end browser flow after every individual task.
- Run full or end-to-end verification only when the user explicitly asks for it, or once at the agreed unified verification checkpoint.
- Prefer focused static checks while editing; keep expensive cross-service verification grouped at the checkpoint.
- Local debugging uses the frontend on `5173`, the Java dev service on `8080`, and the Python dev service on `18000`. Docker remains the final all-service deployment target.
- Preserve unrelated working-tree changes. Do not reset or overwrite files outside the active task.
