import { describe, expect, it } from "vitest";
import { displayRoomMessageText, humanizeDossierText } from "./displayText";

describe("display text helpers", () => {
  it("maps intake dossier internal evidence field codes to readable Chinese", () => {
    const text = humanizeDossierText(
      "仍缺少可信的buyer_evidence、merchant_outbound_photos",
    );

    expect(text).toContain("买家证据材料");
    expect(text).toContain("商家发货前照片");
    expect(text).not.toContain("buyer_evidence");
    expect(text).not.toContain("merchant_outbound_photos");
  });

  it("maps internal dispute enum codes inside immutable room messages", () => {
    const text = displayRoomMessageText(
      "本案当前争议焦点是 SIGNED_NOT_RECEIVED，请补充物流签收记录。",
    );

    expect(text).toContain("物流显示签收但用户称未收到包裹");
    expect(text).not.toContain("SIGNED_NOT_RECEIVED");
  });

  it("maps adjudication recommendation enum codes to readable Chinese", () => {
    const text = humanizeDossierText("RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW");

    expect(text).toContain("补发");
    expect(text).toContain("退款");
    expect(text).not.toContain("RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW");
  });

  it("summarizes raw evidence matrix json in immutable room messages", () => {
    const text = displayRoomMessageText(
      '证据书记官宣读证据卷宗：核心证明矩阵显示：{"evidence_id":"EVIDENCE_001","relation_type":"UNMAPPED","verification_status":"UNVERIFIED"}。',
    );

    expect(text).toContain("证据材料尚未映射到具体争议事实");
    expect(text).toContain("待核验");
    expect(text).not.toContain("evidence_id");
    expect(text).not.toContain("relation_type");
    expect(text).not.toContain("verification_status");
    expect(text).not.toContain("UNMAPPED");
    expect(text).not.toContain("UNVERIFIED");
  });
});
