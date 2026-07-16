# 文件作用：自动化测试文件，验证 test_room_based_document_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "Project Plan"
DOCS = sorted(PLAN.glob("AI_Native履约争端审理系统_*.md"))


# 所属模块：跨服务契约测试 > test_room_based_document_contract；函数角色：模块公开业务函数。
# 具体功能：`corpus` 围绕被测业务场景计算该函数独立负责的业务派生值；关键协作调用：`join`、`path.read_text`。
# 上下游：上游为 本文件的 `test_room_based_final_contract`；下游为 协作调用 `join`、`path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def corpus() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in DOCS)


# 所属模块：跨服务契约测试 > test_room_based_document_contract；函数角色：回归测试用例。
# 具体功能：`test_room_based_final_contract` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `corpus`。
# 系统意义：固定“跨服务契约测试 > test_room_based_document_contract”的可观察契约，防止后续重构改变业务结果。
def test_room_based_final_contract() -> None:
    text = corpus()

    for required in (
        "争议办理总览",
        "争议接待室",
        "证据书记官室",
        "小法庭",
        "传票信箱",
        "PT2H",
        "PT3H",
        "HEARING_PARTY_STAGE_WINDOW=PT20M",
        "settlement_proposal",
        "Last-Event-ID",
    ):
        assert required in text, f"missing room-based final requirement: {required}"

    for prohibited in (
        "主流程为普通履约流",
        "系统接入短信供应商",
        "系统建设全量订单中心",
    ):
        assert prohibited not in text, f"stale or prohibited requirement: {prohibited}"


# 所属模块：跨服务契约测试 > test_room_based_document_contract；函数角色：回归测试用例。
# 具体功能：`test_every_final_document_uses_the_same_product_boundary` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`path.read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `path.read_text`。
# 系统意义：固定“跨服务契约测试 > test_room_based_document_contract”的可观察契约，防止后续重构改变业务结果。
def test_every_final_document_uses_the_same_product_boundary() -> None:
    assert DOCS, "no final project documents found"

    for path in DOCS:
        text = path.read_text(encoding="utf-8")
        if "建设订单中心" in text:
            assert "不建设订单中心" in text
