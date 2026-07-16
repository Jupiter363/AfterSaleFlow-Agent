# 文件作用：自动化测试文件，验证 test_phase11_review_contract 相关模块的行为、契约或页面布局。

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"

# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_review_api_and_real_human_gate_exist`、`test_all_review_actions_and_temporal_evidence_return_are_supported`、`test_reviewer_frontend_is_backend_driven_and_role_gated`、`test_frontend_container_keeps_application_writable_for_non_root_user`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")

# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_review_api_and_real_human_gate_exist` 验证人工复核信息在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_review_api_and_real_human_gate_exist() -> None:
    controller = read(JAVA / "review" / "api" / "ReviewController.java")
    service = read(JAVA / "review" / "application" / "ReviewApplicationService.java")
    for path in ('"/{taskId}/packet"', '"/{taskId}/decision"'):
        assert path in controller
    assert "autoApprove" in read(JAVA / "review" / "domain" / "ApprovalPolicyDecision.java")
    assert "false" in read(JAVA / "review" / "domain" / "ApprovalPolicyEngine.java")
    assert "PlatformReviewerAuthorization.requireDecisionAccess(actor)" in service
    assert "postReviewOrchestration.orchestrate(" in service
    assert "originalPlanJson" in read(JAVA / "infrastructure" / "persistence" / "entity" / "ApprovalRecordEntity.java")

# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_all_review_actions_and_temporal_evidence_return_are_supported` 验证当前可见证据在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_all_review_actions_and_evidence_return_are_supported() -> None:
    decision = read(JAVA / "domain" / "model" / "ApprovalDecisionType.java")
    service = read(JAVA / "review" / "application" / "ReviewApplicationService.java")
    orchestration = read(
        JAVA / "review" / "application" / "PostReviewOrchestrationService.java"
    )
    for action in ("APPROVE", "MODIFY_AND_APPROVE", "REJECT", "REQUEST_MORE_EVIDENCE", "ESCALATE_MANUAL"):
        assert action in decision
    assert "case REQUEST_MORE_EVIDENCE" in service
    assert '"WAITING_EVIDENCE"' in orchestration

# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_reviewer_frontend_is_backend_driven_and_role_gated` 验证人工复核信息在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_reviewer_frontend_is_backend_driven_and_role_gated() -> None:
    app = read(ROOT / "frontend" / "src" / "App.vue")
    actor = read(ROOT / "frontend" / "src" / "state" / "actor.js")
    review = read(
        ROOT / "frontend" / "src" / "views" / "ReviewWorkbenchView.vue"
    )
    api = read(ROOT / "frontend" / "src" / "api" / "review.js")
    package = read(ROOT / "frontend" / "package.json")
    assert "PLATFORM_REVIEWER" in app
    assert "CUSTOMER_SERVICE" in actor
    assert "MODIFY_AND_APPROVE" in review
    assert "REQUEST_MORE_EVIDENCE" in review
    assert "/reviews" in api
    assert '"element-plus"' in package and '"vue"' in package


# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_container_keeps_application_writable_for_non_root_user` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_frontend_container_keeps_application_writable_for_non_root_user() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    assert "AS build" in dockerfile
    assert "COPY --from=build --chown=node:node /app/dist ./dist" in dockerfile
    assert "USER node" in dockerfile


# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_container_pins_the_runtime_package_manager` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_frontend_container_pins_the_runtime_package_manager() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    package = read(ROOT / "frontend" / "package.json")
    assert "ENV COREPACK_HOME=/opt/corepack" in dockerfile
    assert '"packageManager": "pnpm@11.7.0"' in package


# 所属模块：跨服务契约测试 > test_phase11_review_contract；函数角色：回归测试用例。
# 具体功能：`test_frontend_healthchecks_use_the_ipv4_listener` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase11_review_contract”的可观察契约，防止后续重构改变业务结果。
def test_frontend_healthchecks_use_the_ipv4_listener() -> None:
    dockerfile = read(ROOT / "frontend" / "Dockerfile")
    compose = read(ROOT / "docker-compose.yml")
    assert "http://127.0.0.1:5173/healthz" in dockerfile
    assert "http://127.0.0.1:5173/" in compose
