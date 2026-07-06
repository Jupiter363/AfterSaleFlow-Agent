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
});
