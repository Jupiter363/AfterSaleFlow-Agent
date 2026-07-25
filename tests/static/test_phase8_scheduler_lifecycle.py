from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service/src/main/java/com/example/dispute"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_agent_run_detector_uses_a_complete_authoritative_legacy_aggregate() -> None:
    source = _read(
        JAVA / "agentstream/application/AgentRunRecoveryScheduler.java"
    )
    detector = source[
        source.index("private void detectLegacyOwnedRuns()") :
        source.index("private record AgentRunDetection")
    ]

    for marker in (
        "from agent_run",
        "protocol = 'agent_stream.v1'",
        "executor_kind = 'LEGACY_WORKER'",
        "stream_operation is not null",
        "run_status in ('PENDING', 'RUNNING')",
        "candidates != pending + running",
    ):
        assert marker in source
    assert "findTop20" not in detector
    assert "findAll(" not in detector
    assert "worker.execute" not in detector
    assert "failInfrastructure" not in detector


def test_hearing_detector_starts_from_full_legacy_tables_and_fails_closed() -> None:
    source = _read(
        JAVA / "workflow/recovery/hearing/JdbcHearingSchedulerDetector.java"
    )
    for marker in (
        "from hearing_flow_instance flow",
        "flow.flow_status in ('ACTIVE', 'FAILED')",
        "agent_run_status = 'FAILED'",
        "least(",
        "shared_deadline_at,",
        "coalesce(case_deadline_at, shared_deadline_at)",
        ") <= current_timestamp",
        "case_deadline_at < shared_deadline_at",
        "from hearing_flow_action party_action",
        "legacy_candidates.user_id",
        "legacy_candidates.merchant_id",
        "from hearing_flow_artifact draft",
        "draft.artifact_type = 'ADJUDICATION_DRAFT'",
        "left join hearing_flow_instance flow",
        "authoritative_stage not in ('HUMAN_REVIEW_OPEN', 'CLOSED')",
        "projection_stage is distinct from authoritative_stage",
        "projection_sequence is distinct from authoritative_sequence",
        "exact_handoff_recorded",
        "projection_writer_mode not in ('LEGACY', 'TEMPORAL')",
        "authority_lifecycle_status = 'TERMINAL'",
        "authority_process_revision = projection_process_revision + 1",
        "authority_room_revision = projection_room_revision + 1",
        "left join hearing_temporal_projection projection",
        "authority.lifecycle_status = 'ACTIVE'",
        "authority.process_revision = projection.process_revision",
        "authority.room_revision = projection.room_revision",
        "projection.temporal_workflow_id is not distinct from coalesce(",
        "projection.temporal_run_id is not distinct from coalesce(",
        "projection.temporal_build_or_deployment is not distinct from coalesce(",
        "'legacy-java.v1'",
        "projection_id is null",
        "authority_id is null",
    ):
        assert marker in source
    assert "limit " not in source.lower()
    assert "@Transactional(readOnly = true)" in source
    assert "returned no " in source


def test_detector_paths_have_no_business_or_time_authority() -> None:
    deadline = _read(
        JAVA / "hearing/application/HearingFlowDeadlineScheduler.java"
    )
    handoff = _read(
        JAVA / "hearing/application/HearingReviewHandoffRecoveryScheduler.java"
    )
    detector = _read(
        JAVA / "workflow/recovery/hearing/JdbcHearingSchedulerDetector.java"
    ).lower()

    assert "case DETECT_ONLY -> observeLegacyCandidateParity();" in deadline
    assert "case DETECT_ONLY -> observeLegacyCandidateParity();" in handoff
    for statement in (" insert ", " update ", " delete ", " for update"):
        assert statement not in detector


def test_drained_off_keeps_temporal_identity_and_defaults_stay_executing() -> None:
    control = _read(
        JAVA / "workflow/recovery/hearing/HearingSchedulerControl.java"
    )
    properties = _read(JAVA / "workflow/config/AgentRunV2Properties.java")
    application = _read(ROOT / "java-api-service/src/main/resources/application.yml")
    deadline = _read(
        JAVA / "hearing/application/HearingFlowDeadlineScheduler.java"
    )
    handoff = _read(
        JAVA / "hearing/application/HearingReviewHandoffRecoveryScheduler.java"
    )

    drained_off = control[
        control.index("public static HearingSchedulerControl drainedOff()") :
        control.index("public Decision decision()")
    ]
    assert "SchedulerMode.OFF" in drained_off
    assert "HearingWriterMode.TEMPORAL" in drained_off
    assert "HearingWriterMode.LEGACY" not in drained_off
    assert "OFF must preserve the drained TEMPORAL Hearing writer identity" in control
    assert "TEMPORAL Hearing writer cannot use the legacy scheduler executor" in control
    assert "scheduler-mode: ${APP_AGENT_RUN_V2_SCHEDULER_MODE:EXECUTOR}" in application
    assert "${dispute.hearing-flow-timeout-scheduler-mode:EXECUTOR}" in deadline
    assert "${dispute.hearing-review-handoff-scheduler-mode:EXECUTOR}" in handoff
    assert "${dispute.hearing-scheduler-writer-mode:LEGACY}" in deadline
    assert "${dispute.hearing-scheduler-writer-mode:LEGACY}" in handoff
