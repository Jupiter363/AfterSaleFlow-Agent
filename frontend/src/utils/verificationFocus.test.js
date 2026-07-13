// 文件作用：自动化测试文件，验证 verificationFocus.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { describe, expect, it } from "vitest";
import { normalizeVerificationFocus } from "./verificationFocus";

// 业务位置：【前端应用】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
describe("normalizeVerificationFocus", () => {
  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("merges material names, gaps, questions and actions into canonical action phrases", () => {
    const result = normalizeVerificationFocus([
      "开箱视频/照片",
      "商品页面截图",
      "沟通记录",
      "物流签收细节",
      "缺少开箱视频或照片以客观记录磨损情况",
      "缺少商品页面完整截图或快照",
      "缺少用户与商家的聊天记录",
      "缺少物流签收状态和用户是否当场验货的信息",
      "请问您是否有收到包裹时的开箱视频或照片？",
      "能否提供商品页面的完整截图？",
      "您与商家的沟通记录是否可以提供？",
      "物流显示签收了吗？您是签收后多久打开检查的？",
      "开箱视频",
      "照片",
      "核对商品页面描述截图或快照",
      "获取用户开箱照片或视频",
      "核实物流签收时间与用户开启包裹的间隔",
      "获取用户与商家的完整沟通记录",
    ]);

    expect(result).toEqual([
      "核验商品异常照片或开箱视频，确认商品状态及形成时间",
      "核对商品页面完整描述、截图或快照",
      "核验用户与商家的完整沟通记录",
      "核验物流签收及投递记录，确认签收人身份、位置、时间与开箱检查间隔",
    ]);
    expect(result.every((item) => !/[？?]$/u.test(item))).toBe(true);
  });

  // 业务位置：【前端应用】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由、API 和本地状态 正确进入 售后纠纷处理界面。上游：路由、API 和本地状态。下游：售后纠纷处理界面。边界：前端不拥有裁判和执行权限。
  it("keeps unrelated focus items distinct while enforcing action phrasing", () => {
    expect(normalizeVerificationFocus(["责任主体", "核验事项 1", "责任主体"])).toEqual([
      "核验责任主体",
      "核验事项 1",
    ]);
  });

  it("removes workflow status copy from business verification focus", () => {
    expect(
      normalizeVerificationFocus([
        "等待接待官完成案件详情整理",
        "信息完整度已达到提交阈值",
        "核验商品异常照片或开箱视频，确认商品状态及形成时间",
      ]),
    ).toEqual(["核验商品异常照片或开箱视频，确认商品状态及形成时间"]);
  });
});
