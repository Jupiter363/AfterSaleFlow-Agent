from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SELECTOR = ROOT / (
    "apps/domain-service/src/main/java/com/example/dispute/workflow/shadow/evidence/"
    "EvidenceEpochSelector.java"
)
ROLLBACK = ROOT / (
    "apps/domain-service/src/main/java/com/example/dispute/workflow/shadow/evidence/"
    "EvidenceCutoverRollback.java"
)
RECOVERY_TESTS = [
    ROOT
    / "apps/domain-service/src/test/java/com/example/dispute/workflow/recovery/"
    / name
    for name in (
        "EvidenceTemporalCutoverIntegrationTest.java",
        "EvidenceCutoverRollbackTest.java",
        "EvidenceRoomWorkflowRecoveryTest.java",
    )
]


def test_selector_is_closed_to_disabled_or_java_signed_synthetic_shadow():
    source = SELECTOR.read_text(encoding="utf-8")

    for marker in (
        "RuntimeMode.DISABLED",
        "RuntimeMode.SHADOW",
        "JAVA_SIGNED_SYNTHETIC",
        "UNKNOWN_SELECTION_VERSION",
        "UNSIGNED_SYNTHETIC_FORBIDDEN",
        "STALE_JAVA_AUTHORITY",
        "REAL_CASE_FORBIDDEN",
        "LEGACY_TIMER_INVARIANT_VIOLATION",
        "legacyTimerStartCount != 0",
    ):
        assert marker in source

    assert "RuntimeMode.TEMPORAL" not in source
    assert "WriterMode.TEMPORAL" not in source
    assert "new EvidenceRoomWorkflow" not in source


def test_rollback_preserves_java_truth_and_never_infers_formal_state():
    source = ROLLBACK.read_text(encoding="utf-8")

    for marker in (
        "request.javaTruth()",
        "ACTIVITY_RESPONSE_LOST",
        "RECEIPT_NOT_IN_JAVA_TRUTH",
        "RECEIPT_BINDING_MISMATCH",
        "legacyTimerStartCount != 0",
        "formalWriteCount != 0",
        "checkpointsRetained",
        "ledgersRetained",
        "ordinal == 1 || ordinal == 8 || ordinal == 100",
    ):
        assert marker in source

    for forbidden in (
        "RuntimeMode.TEMPORAL",
        "WriterMode.TEMPORAL",
        "EvidenceSubmissionService",
        "EvidenceCompletionService",
        "EvidenceGraphResultFinalizer",
    ):
        assert forbidden not in source


def test_focused_recovery_tests_cover_required_boundaries():
    sources = {path.name: path.read_text(encoding="utf-8") for path in RECOVERY_TESTS}
    rollback_test = sources["EvidenceCutoverRollbackTest.java"]
    selector_test = sources["EvidenceTemporalCutoverIntegrationTest.java"]
    workflow_test = sources["EvidenceRoomWorkflowRecoveryTest.java"]

    assert "@ValueSource(ints = {1, 8, 100})" in rollback_test
    assert "FailureBoundary.ITEM_CRASH" in rollback_test
    assert "FailureBoundary.TIMER_RACE" in rollback_test
    assert "FailureBoundary.ACTIVITY_RESPONSE_LOST" in rollback_test
    assert "JavaReceiptObservation.notCommitted()" in rollback_test
    assert "JavaReceiptObservation.committed(RECEIPT)" in rollback_test
    assert "TrafficAuthorization.UNSIGNED_SYNTHETIC" in rollback_test
    assert "TrafficAuthorization.JAVA_SIGNED_REAL_CASE" in rollback_test

    assert "UNKNOWN_SELECTION_VERSION" in selector_test
    assert "UNSIGNED_SYNTHETIC_FORBIDDEN" in selector_test
    assert "STALE_JAVA_AUTHORITY" in selector_test
    assert "REAL_CASE_FORBIDDEN" in selector_test
    assert "timerCount(environment.getWorkflowClient(), workflowId)).isOne()" in selector_test

    assert "WorkflowReplayer.replayWorkflowExecution" in workflow_test
    assert "rollbackOutcome.preservedJavaTruth()" in workflow_test
    assert "rollbackOutcome.legacyTimerStartCount()).isZero()" in workflow_test


def test_public_submission_limit_remains_fifty():
    request = (
        ROOT
        / "apps/domain-service/src/main/java/com/example/dispute/evidence/api/"
        / "EvidenceSubmissionRequest.java"
    ).read_text(encoding="utf-8")

    assert "@Size(min = 1, max = 50) List<String> evidenceIds" in request
    assert "@Size(min = 1, max = 100) List<String> evidenceIds" not in request
