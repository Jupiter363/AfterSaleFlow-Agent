# Target-architecture E2E deployment

Status: current isolated deployment tooling. The UAT-aligned release identity is
`all-rooms.target-e2e.v2` / `target-e2e-graph.2026-08-18.3` /
`target-e2e-checkpoint.v2`; Intake uses `PARALLEL_FRAMES_V1` and `agent-stream.v4`.
The model profile resolves to `qwen3.8-flash`, with thinking disabled and strict JSON
Schema enabled.

This Compose project is independent from `docker-compose.yml`: it uses a run-scoped project name,
named networks and volumes, an isolated gateway port, separate Domain/Graph/Temporal PostgreSQL
instances, and no production endpoint or credential.

Provisioning requires a self-hashed v2 image lock. Every image records its registry manifest,
config, ordered layers, source revision, and build ID; application image source revisions must
equal the exact candidate SHA. Tags, `latest`, incomplete provenance, and v1 locks are rejected.

```json
{
  "schema_version": "target-e2e-image-lock.v2",
  "candidate_sha": "<exact 40-character candidate Git SHA>",
  "source_revision": "<same candidate Git SHA>",
  "build_provenance": {
    "builder_id": "<builder identity>",
    "invocation_id": "<unique build invocation>",
    "source_tree_sha256": "sha256:<digest>",
    "built_at": "<ISO-8601 timestamp>",
    "attestation_type": "<provenance format>",
    "attestation_digest": "sha256:<digest>"
  },
  "images": {
    "java": {
      "reference": "registry.example/after-sale-java@sha256:<manifest digest>",
      "manifest_digest": "sha256:<manifest digest>",
      "config_digest": "sha256:<config digest>",
      "layer_digests": ["sha256:<layer digest>"],
      "source_revision": "<candidate Git SHA>",
      "build_id": "<build identity>"
    }
  },
  "self_hash": "<SHA-256 of canonical JSON without self_hash>"
}
```

The `images` object must contain the exact inventory accepted by `common.py`; the Java record
above illustrates the required record shape for every entry.

Create the lock from a clean checkout of the exact candidate with `build_image_lock.py`. The
`--base-images` file is a strict JSON object containing the eight non-application keys accepted by
`common.py` (`postgres`, `redis`, `minio`, `minio_mc`, `elasticsearch`, `temporal`, `nginx`, and
`curl`); every value must already be an immutable `repository@sha256:...` reference. The command
builds and pushes the Java target artifact plus Python, OCR, and frontend images, pulls every image
by digest, measures config and ordered layer identities, and writes a self-hashed lock together
with its bound build attestation to a new directory outside the worktree.

```text
python tools/uat/target-e2e/build_image_lock.py \
  --candidate <exact-40-char-SHA> \
  --base-images <external-base-images.json> \
  --repository-prefix <registry/repository-prefix> \
  --output-directory <new-external-output-directory> \
  --invocation-id <unique-build-invocation>
```

Provisioning discovers an existing OpenSSL configuration from the selected executable (including
Conda's `Library/bin/openssl.exe` to `Library/ssl/openssl.cnf` layout) and explicitly supplies it as
`OPENSSL_CONF` to every OpenSSL subprocess. Missing, empty, oversized, or unreadable configs block
provisioning; no activated Conda shell or manual environment repair is required.

Use `provision.py`, then pass its printed external env-file path to `preflight.py` and `up.py`.
Java writes its fresh ES256 final-evidence JWS to the run-local
`evidence/inbox/<case-id>.java-evidence.jws`; validate it with `assert_evidence.py --env-file ...
--case-id ...`. The assertion path cannot accept an arbitrary file or URL. It binds the Java JWS
to the signed append-only harness ledger, live container/image identities, activation and
environment generation, Compose project, Temporal namespace, both database identities, case, and
run nonce.

Finish with `teardown.py`. It requires forensic export, validates the active host and port locks,
and removes only the exact labeled container IDs, network names, and volume names reserved for the
run. It never uses a broad Compose teardown.

The unified checkpoint has an executable wrapper in `batch4.py`. Journey/recovery and drain/revoke
drivers are supplied as strict JSON argv arrays; the wrapper appends `--env-file`, `--case-id`, and
`--stage`, runs the fixed readiness/assertion/forensic sequence, and refuses to emit evidence unless
`evidence/batch-4-scenario.json` proves every frozen Batch 4 assertion and preserves the external
promotion ceiling. A successful run creates `p9.0-evidence.json` with
`PASS_AWAITING_ACCEPTANCE`. It does not self-accept.

```text
python tools/uat/target-e2e/batch4.py --env-file <external-env> \
  --case-id CASE_P9_SYNTHETIC_0001 \
  --journey-command <journey-argv.json> --drain-command <drain-argv.json>
```

Only a separate P-256 key, distinct from the run harness key, can create the engineering acceptance
object. The current source baseline subsequently completed a fresh browser six-station UAT as
`CASE_P9_6A98633E_11`. This is isolated candidate evidence: production routing, feature flags and
core platform versions still require an explicit release decision.

```text
python tools/uat/target-e2e/p9_gate.py accept --env-file <external-env> \
  --acceptance-private-key <external-private.pem> \
  --acceptance-public-key <external-public.pem> \
  --acceptance-key-id <independent-reviewer-key-id>
```
