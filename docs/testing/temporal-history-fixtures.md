# Captured Temporal histories

These fixtures contain synthetic test data only. They are immutable replay inputs for
worker compatibility checks, not golden business outputs.

`case-process-v1.json` captures a closed first run containing Update-With-Start,
room-child routing, the 24-hour timer, and Continue-As-New. The matching replay gate is
`CaseProcessWorkflowReplayTest`.

Capture a replacement from the time-skipping scenario with
`WorkflowClient.fetchHistory(workflowId, runId).toJson(true)`. Review the payload for
sensitive data and normalize worker identity before committing it. When a deployed
workflow version must remain supported, add a new fixture instead of overwriting its
existing history.
