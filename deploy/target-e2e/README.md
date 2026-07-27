# Target-architecture E2E deployment

This Compose project is independent from `docker-compose.yml`: it uses a run-scoped project name,
named networks and volumes, an isolated gateway port, separate Domain/Graph/Temporal PostgreSQL
instances, and no production endpoint or credential.

Provisioning requires an image lock with the exact schema below. Every reference must include a
registry name and `@sha256:` digest; tags and `latest` are rejected.

```json
{
  "schema_version": "target-e2e-image-lock.v1",
  "build_id": "<exact 40-character candidate Git SHA>",
  "images": {
    "postgres": "registry.example/postgres@sha256:<digest>",
    "redis": "registry.example/redis@sha256:<digest>",
    "minio": "registry.example/minio@sha256:<digest>",
    "minio_mc": "registry.example/minio-mc@sha256:<digest>",
    "elasticsearch": "registry.example/elasticsearch@sha256:<digest>",
    "temporal": "registry.example/temporal@sha256:<digest>",
    "java": "registry.example/after-sale-java@sha256:<digest>",
    "python": "registry.example/after-sale-python@sha256:<digest>",
    "ocr": "registry.example/after-sale-ocr@sha256:<digest>",
    "frontend": "registry.example/after-sale-frontend@sha256:<digest>",
    "nginx": "registry.example/nginx@sha256:<digest>",
    "curl": "registry.example/curl@sha256:<digest>"
  }
}
```

Use `provision.py`, then pass its printed external env-file path to `preflight.py`, `up.py`,
`assert_evidence.py`, and finally `teardown.py`. Teardown refuses to remove volumes unless forensic
export succeeds. `assert_evidence.py` never skips or substitutes a legacy path; the application
gates in `application-contract-gates.json` remain blocking until runtime evidence proves them.
