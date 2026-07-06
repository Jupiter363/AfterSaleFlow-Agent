import { describe, expect, it } from "vitest";
import { humanizeDossierText } from "./displayText";

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
});
