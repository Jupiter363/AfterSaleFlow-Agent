// 文件作用：自动化测试文件，验证 displayText.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it } from "vitest";
import { displayRoomMessageText, humanizeDossierText } from "./displayText";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("display text helpers", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("maps intake dossier internal evidence field codes to readable Chinese", () => {
    const text = humanizeDossierText(
      "仍缺少可信的buyer_evidence、merchant_outbound_photos",
    );

    expect(text).toContain("买家证据材料");
    expect(text).toContain("商家发货前照片");
    expect(text).not.toContain("buyer_evidence");
    expect(text).not.toContain("merchant_outbound_photos");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("maps internal dispute enum codes inside immutable room messages", () => {
    const text = displayRoomMessageText(
      "本案当前争议焦点是 SIGNED_NOT_RECEIVED，请补充物流签收记录。",
    );

    expect(text).toContain("物流显示签收但用户称未收到包裹");
    expect(text).not.toContain("SIGNED_NOT_RECEIVED");
  });

  it("preserves evidence ids until the room can map them to visible filenames", () => {
    const text = displayRoomMessageText(
      "法官引用 EVIDENCE_USER_REAL，并要求 MERCHANT 补充说明。",
    );

    expect(text).toContain("EVIDENCE_USER_REAL");
    expect(text).toContain("商家");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("maps adjudication recommendation enum codes to readable Chinese", () => {
    const text = humanizeDossierText("RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW");

    expect(text).toContain("补发");
    expect(text).toContain("退款");
    expect(text).not.toContain("RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW");
  });

  it("localizes draft validation fallbacks for the read-only scroll", () => {
    expect(humanizeDossierText("UNDETERMINED")).toBe("待终审确认");
    expect(
      humanizeDossierText(
        "Structured agent output could not be validated. No automated finding was accepted.",
      ),
    ).toContain("需由终审人工复核");
    expect(
      humanizeDossierText(
        "Review the failed final-convergence structured output manually.",
      ),
    ).toContain("请人工复核");
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
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
