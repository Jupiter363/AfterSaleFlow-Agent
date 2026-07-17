# Temporal Observability and Payload Codec

This runbook covers the Phase 1 Java control-plane tracing, visibility, and payload
protection contract. API, CONTROL Worker, and AGENT Worker use the same configuration.

## Search Attributes

Provision these custom Search Attributes as `Keyword` fields before setting
`TEMPORAL_SEARCH_ATTRIBUTES_ENABLED=true` on any API instance:

```text
DisputeTenantSurrogate
DisputeCaseSurrogate
DisputeWorkflowKind
DisputeMacroPhase
DisputeRoomType
DisputeContractVersion
DisputeTerminalStatus
```

Registration is namespace infrastructure, not an application startup side effect. For
Temporal CLI installations that support the operator command, register each field with:

```bash
temporal operator search-attribute create \
  --namespace "$TEMPORAL_NAMESPACE" \
  --name DisputeWorkflowKind \
  --type Keyword
```

Repeat for the complete list and verify it with
`temporal operator search-attribute list`. The application emits only the allowlisted
surrogate and enum/version fields. Command IDs, `traceparent`, actor data, payload URIs,
messages, and evidence content are prohibited.

## OpenTelemetry

Set `OTEL_TRACING_ENABLED=true` only when an OTLP endpoint is reachable. HTTP server
spans are created by Spring observability. The durable command stores the current W3C
`traceparent`; the outbox restores that parent before Temporal delivery; Temporal
client/worker interceptors then propagate the context into Workflow and Activity
execution. Export failure must not fail a command.

The Phase 8 production topology supplies two collectors. Until then local and Compose
export is opt-in through `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` and
`OTEL_TRACES_SAMPLING_PROBABILITY`.

The Spring profiles publish distinct resource service names: `java-api-service`,
`java-control-worker`, and `java-agent-worker`. Do not collapse them in collector
resource rewriting; they have different saturation signals and failure ownership.

## Payload Encryption

`TEMPORAL_PAYLOAD_CODEC_MODE` has three fail-closed rollout states:

| Mode | Read | Write |
| --- | --- | --- |
| `DISABLED` | legacy plaintext | legacy plaintext |
| `DECRYPT_ONLY` | legacy plaintext and configured encrypted keys | legacy plaintext |
| `ENCRYPT` | legacy plaintext and configured encrypted keys | AES-256-GCM |

`TEMPORAL_PAYLOAD_CODEC_ACTIVE_KEY_ID` is a non-secret versioned key ID.
`TEMPORAL_PAYLOAD_CODEC_ACTIVE_KEY_BASE64` is a 32-byte key supplied as Base64 from a
mounted KMS/Vault secret. Never commit key material.

API, CONTROL Worker, and AGENT Worker must receive the same active key and read keyring.
Existing plaintext histories remain readable for replay. Malformed ciphertext, an
unknown key ID, or failed authentication is rejected.

Rotate without breaking active histories:

1. Deploy every process in `DECRYPT_ONLY` with the new key plus every still-referenced
   old key in `app.temporal.payload-protection.decryption-keys` from external Spring
   configuration. New payloads are still plaintext during this expansion.
2. Confirm every API/Worker has the expanded read keyring, then change the active key ID,
   active key material, and mode to `ENCRYPT`. Instances still on `DECRYPT_ONLY` can read
   payloads emitted during this rolling switch.
3. Run captured-History replay and old/new key fixtures before promoting the new Worker
   build.
4. Remove an old decryption key only after Temporal retention and the active-reference
   audit prove no readable history still uses it.

Never skip `DECRYPT_ONLY` or move only one process directly from `DISABLED` to `ENCRYPT`.
That creates histories another Worker cannot decode and is a failed deployment, not a
recoverable application error.
