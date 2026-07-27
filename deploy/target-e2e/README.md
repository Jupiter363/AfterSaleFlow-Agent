# Target-architecture E2E deployment

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
