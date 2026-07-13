# 文件作用：自动化测试文件，验证 test_phase14_frontend_contract 相关模块的行为、契约或页面布局。

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


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_frontend_has_real_routes_for_all_phase_14_workspaces`、`test_frontend_uses_only_nginx_proxy_paths_and_no_service_secrets`、`test_evidence_upload_submission_and_review_are_backend_driven`、`test_case_detail_exposes_dossier_timeline_draft_execution_and_audit`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_has_real_routes_for_all_phase_14_workspaces` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase14_frontend_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_uses_only_nginx_proxy_paths_and_no_service_secrets` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`join`、`path.read_text`、`FRONTEND.rglob`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase14_frontend_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：回归测试用例。
# 具体功能：`test_evidence_upload_submission_and_review_are_backend_driven` 读取并按案件、角色或会话范围筛选当前可见证据。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase14_frontend_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：回归测试用例。
# 具体功能：`test_case_detail_exposes_dossier_timeline_draft_execution_and_audit` 验证案件卷宗在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase14_frontend_contract”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：跨服务契约测试 > test_phase14_frontend_contract；函数角色：回归测试用例。
# 具体功能：`test_backend_supports_role_scoped_case_index_and_staff_audit_query` 读取并按案件、角色或会话范围筛选角色权限。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase14_frontend_contract”的可观察契约，防止后续重构改变业务结果。
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
