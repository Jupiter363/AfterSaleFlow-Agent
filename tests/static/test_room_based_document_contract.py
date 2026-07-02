from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "Project Plan"
DOCS = sorted(PLAN.glob("AI_Native履约争端审理系统_*.md"))


def corpus() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in DOCS)


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
        "MAX_HEARING_ROUNDS=3",
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


def test_every_final_document_uses_the_same_product_boundary() -> None:
    assert DOCS, "no final project documents found"

    for path in DOCS:
        text = path.read_text(encoding="utf-8")
        if "建设订单中心" in text:
            assert "不建设订单中心" in text
