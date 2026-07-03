from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "frontend" / "src"
JAVA = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "dispute"
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_frontend_has_real_routes_for_all_phase_14_workspaces() -> None:
    router = read(FRONTEND / "router.js")
    for route in (
        'path: "/cases"',
        'path: "/cases/:caseId"',
        'path: "/cases/:caseId/submissions/user"',
        'path: "/cases/:caseId/submissions/merchant"',
        'path: "/review"',
    ):
        assert route in router


def test_frontend_uses_only_nginx_proxy_paths_and_no_service_secrets() -> None:
    client = read(FRONTEND / "api" / "client.js")
    environment = read(ROOT / "frontend" / ".env.example")
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in FRONTEND.rglob("*")
        if path.is_file()
    )
    assert 'VITE_API_BASE_URL || "/api"' in client
    assert "VITE_API_BASE_URL=/api" in environment
    assert "JAVA_SERVICE_SECRET" not in source
    assert "PYTHON_AGENT_SERVICE_SECRET" not in source
    assert "LITELLM_MASTER_KEY" not in source


def test_evidence_upload_submission_and_review_are_backend_driven() -> None:
    cases = read(FRONTEND / "api" / "cases.js")
    submission = read(FRONTEND / "views" / "SubmissionView.vue")
    review = read(FRONTEND / "views" / "ReviewWorkbenchView.vue")
    assert 'body.append("file", file)' in cases
    assert "/rooms/EVIDENCE/messages" in cases
    assert "Idempotency-Key" in cases
    assert "pending_requests_json" in submission
    assert "ElMessageBox.confirm" in review
    for decision in (
        "APPROVE",
        "MODIFY_AND_APPROVE",
        "REJECT",
        "REQUEST_MORE_EVIDENCE",
        "ESCALATE_MANUAL",
    ):
        assert decision in review


def test_case_detail_exposes_dossier_timeline_draft_execution_and_audit() -> None:
    detail = read(FRONTEND / "views" / "CaseDetailView.vue")
    for contract in (
        'name="evidence"',
        'name="timeline"',
        'name="draft"',
        'name="actions"',
        'name="audit"',
        "fact_findings",
        "evidence_assessment",
        "policy_application",
        "reviewer_attention",
        "desensitized",
    ):
        assert contract in detail


def test_backend_supports_role_scoped_case_index_and_staff_audit_query() -> None:
    cases = read(
        JAVA
        / "caseintake"
        / "application"
        / "CaseApplicationService.java"
    )
    audit = read(JAVA / "audit" / "application" / "AuditQueryService.java")
    dossier = read(
        JAVA / "evidence" / "application" / "BuildDossierResult.java"
    )
    assert 'root.get("userId")' in cases
    assert 'root.get("merchantId")' in cases
    assert "PageRequest.of" in cases
    assert "PLATFORM_REVIEWER" in audit
    assert "actor cannot view case audit logs" in audit
    assert "List<EvidenceView> evidences" in dossier
