// 文件作用：自动化测试文件，验证 SummonsMailbox.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import SummonsMailbox from "./SummonsMailbox.vue";

const notifications = [
  {
    id: "NOTICE_1",
    case_id: "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
    notification_type: "DISPUTE_SUMMONS",
    title: "争议传票",
    body: "请进入证据书记官室提交材料。",
    deep_link: "/disputes/CASE_1/evidence",
    read: false,
    created_at: "2026-07-03T10:00:00Z",
  },
  {
    id: "NOTICE_2",
    case_id: "CASE_1",
    notification_type: "HEARING_OPENED",
    title: "小法庭已开放",
    body: "双方可进入小法庭。",
    deep_link: "/disputes/CASE_1/hearing",
    read: true,
    created_at: "2026-07-03T11:00:00Z",
  },
];

// 业务位置：【案件通知】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
describe("SummonsMailbox", () => {
  // 业务位置：【案件通知】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
  it("shows unread summons and deep-links into the relevant room", async () => {
    const wrapper = mount(SummonsMailbox, {
      props: { notifications, defaultOpen: true },
    });

    expect(wrapper.get("[data-unread-count]").text()).toBe("1");
    expect(wrapper.text()).toContain("争议传票");
    expect(wrapper.text()).toContain("小法庭已开放");
    expect(wrapper.get("[data-case-label]").text()).toContain(
      "CASE_adcb…",
    );
    expect(wrapper.get("[data-case-label]").attributes("title")).toBe(
      "CASE_adcb56b853f248cd8c0cbfed4a2daf8a",
    );

    await wrapper
      .get('[data-notification-id="NOTICE_1"] [data-open-notification]')
      .trigger("click");

    expect(wrapper.emitted("open-notification")?.[0]?.[0]).toEqual(
      notifications[0],
    );
    expect(wrapper.emitted("mark-read")?.[0]).toEqual(["NOTICE_1"]);

    await wrapper.get("[data-mark-all-read]").trigger("click");
    expect(wrapper.emitted("mark-all-read")).toHaveLength(1);

    await wrapper.get('[data-delete-notification="NOTICE_1"]').trigger("click");
    expect(wrapper.emitted("delete-notification")?.[0]).toEqual(["NOTICE_1"]);
  });

  // 业务位置：【案件通知】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
  it("disables only the notification currently being deleted", () => {
    const wrapper = mount(SummonsMailbox, {
      props: {
        notifications,
        defaultOpen: true,
        deletingNotificationIds: ["NOTICE_1"],
      },
    });

    expect(
      wrapper.get('[data-delete-notification="NOTICE_1"]').attributes(
        "disabled",
      ),
    ).toBeDefined();
    expect(
      wrapper.get('[data-delete-notification="NOTICE_2"]').attributes(
        "disabled",
      ),
    ).toBeUndefined();
  });

  // 业务位置：【案件通知】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 生命周期事件和目标角色 正确进入 站内通知视图和已读/关闭状态。上游：生命周期事件和目标角色。下游：站内通知视图和已读/关闭状态。边界：通知受角色和案件可见性限制。
  it("renders loading, empty and error states", async () => {
    const wrapper = mount(SummonsMailbox, {
      props: { notifications: [], defaultOpen: true, loading: true },
    });
    expect(wrapper.text()).toContain("正在整理传票");

    await wrapper.setProps({ loading: false });
    expect(wrapper.text()).toContain("信箱里暂时没有新消息");

    await wrapper.setProps({ error: "信箱暂时无法连接" });
    expect(wrapper.text()).toContain("信箱暂时无法连接");
  });
});
