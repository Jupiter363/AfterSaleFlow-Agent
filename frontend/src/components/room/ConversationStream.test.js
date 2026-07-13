// 文件作用：自动化测试文件，验证 ConversationStream.test 相关模块的行为、契约或页面布局。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import ConversationStream from "./ConversationStream.vue";

// 业务位置：【Java 房间协作】describe：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
describe("ConversationStream", () => {
  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("renders an incremental agent message and lets its durable run record replace it", async () => {
    const run = {
      runId: "AGENT_RUN_1",
      status: "STREAMING",
      content: "正在逐步生成回复",
      agentLabel: "争议接待官",
      senderRole: "CUSTOMER_SERVICE",
      activeCardKey: "default",
      cardOrder: ["default"],
      cards: {
        default: {
          key: "default",
          senderRole: "CUSTOMER_SERVICE",
          content: "正在逐步生成回复",
        },
      },
    };
    const wrapper = mount(ConversationStream, {
      props: { messages: [], streamingRuns: [run] },
    });

    expect(wrapper.get("[data-agent-streaming-message]").text()).toContain(
      "正在逐步生成回复",
    );

    await wrapper.setProps({
      messages: [{
        id: "MESSAGE_FINAL",
        sequence_no: 1,
        sender_role: "CUSTOMER_SERVICE",
        message_type: "AGENT_MESSAGE",
        message_text: "正式回复",
        agent_run_id: "AGENT_RUN_1",
      }],
      streamingRuns: [],
    });

    expect(wrapper.find("[data-agent-streaming-message]").exists()).toBe(false);
    expect(wrapper.text()).toContain("正式回复");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("renders immutable messages in sequence and emits a new party statement", async () => {
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_2",
            sequence_no: 2,
            sender_role: "MERCHANT",
            message_text: "商品已按地址发出。",
          },
          {
            id: "MESSAGE_1",
            sequence_no: 1,
            sender_role: "USER",
            message_text: "我没有收到包裹。",
          },
        ],
      },
    });

    expect(
      wrapper.findAll("[data-room-message]").map((node) => node.text()),
    ).toEqual([
      expect.stringContaining("我没有收到包裹"),
      expect.stringContaining("商品已按地址发出"),
    ]);
    expect(
      wrapper.findAll("[data-room-message] header small").map((node) => node.text()),
    ).toEqual(["#1", "#2"]);

    await wrapper.get("textarea").setValue("请核对签收照片。");
    await wrapper.get("[data-send-message]").trigger("submit");

    expect(wrapper.emitted("submit")?.[0]?.[0]).toEqual({
      message_type: "PARTY_TEXT",
      text: "请核对签收照片。",
      attachment_refs: [],
    });
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("keeps immutable corrupted history readable without showing question-mark noise", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_BAD_ENCODING",
            sequence_no: 1,
            sender_role: "USER",
            message_text: "????????,????????????",
          },
        ],
      },
    });

    expect(wrapper.text()).toContain("历史消息编码异常");
    expect(wrapper.text()).not.toContain("????????");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("renders internal dispute enum codes as Chinese labels in immutable messages", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_INTERNAL_CODE",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_text:
              "本案当前争议焦点是 SIGNED_NOT_RECEIVED，请补充物流签收记录。",
          },
        ],
      },
    });

    expect(wrapper.text()).toContain("物流显示签收但用户称未收到包裹");
    expect(wrapper.text()).not.toContain("SIGNED_NOT_RECEIVED");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("renders sender roles as user-facing Chinese labels", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_AGENT",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_text: "请继续补充证据。",
          },
          {
            id: "MESSAGE_MERCHANT",
            sequence_no: 2,
            sender_role: "MERCHANT",
            message_text: "我方需要核对售后记录。",
          },
        ],
      },
    });

    expect(wrapper.text()).toContain("争议接待官");
    expect(wrapper.text()).toContain("商家");
    expect(wrapper.text()).not.toContain("CUSTOMER_SERVICE");
    expect(wrapper.text()).not.toContain("MERCHANT");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("can override generic agent messages with a room-specific digital human label", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        agentLabel: "证据书记官",
        messages: [
          {
            id: "MESSAGE_EVIDENCE_CLERK",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_type: "AGENT_MESSAGE",
            message_text: "请补充原始文件。",
          },
        ],
      },
    });

    expect(wrapper.text()).toContain("证据书记官");
    expect(wrapper.text()).not.toContain("争议接待官");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("places intake officer messages on the left and party messages on the right", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_AGENT",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_text: "请继续补充证据。",
          },
          {
            id: "MESSAGE_MERCHANT",
            sequence_no: 2,
            sender_role: "MERCHANT",
            message_text: "我方需要核对售后记录。",
          },
        ],
      },
    });

    const [agentMessage, merchantMessage] = wrapper.findAll("[data-room-message]");

    expect(agentMessage.classes()).toContain("conversation-stream__message--agent");
    expect(agentMessage.attributes("data-message-lane")).toBe("left");
    expect(merchantMessage.classes()).toContain("conversation-stream__message--party");
    expect(merchantMessage.attributes("data-message-lane")).toBe("right");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("keeps history inside a scrollable message rail", () => {
    const source = readFileSync("src/components/room/ConversationStream.vue", "utf8");

    expect(source).toContain("grid-template-rows: minmax(0, 1fr) auto;");
    expect(source).toContain("overflow-y: auto;");
    expect(source).toContain("overflow-x: hidden;");
    expect(source).toContain("overscroll-behavior: contain;");
    expect(source).toContain("scrollbar-gutter: stable;");
    expect(source).toContain("height: 132px;");
    expect(source).toContain("grid-template-rows: 72px minmax(44px, 1fr);");
    expect(source).toContain("gap: 2px;");
    expect(source).toContain("padding: 6px 10px;");
    expect(source).toContain("height: 72px;");
    expect(source).toContain("max-height: 72px;");
    expect(source).toContain("resize: none;");
    expect(source).toContain("flex: 0 0 auto;");
    expect(source).toContain("min-height: 44px;");
    expect(source).toContain("white-space: nowrap;");
    expect(source).toContain("overflow-wrap: anywhere;");
    expect(source).toContain("word-break: break-word;");
    expect(source).toContain("white-space: pre-wrap;");
    expect(source).toMatch(
      /@media \(max-width: 620px\)[\s\S]*?max-width: 94%;/,
    );
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("keeps a complete unbroken long message in the document", () => {
    const longMessage = "A".repeat(1000);
    const wrapper = mount(ConversationStream, {
      props: {
        messages: [
          {
            id: "MESSAGE_LONG",
            sequence_no: 1,
            sender_role: "USER",
            message_type: "PARTY_TEXT",
            message_text: longMessage,
          },
        ],
      },
    });

    expect(wrapper.get("[data-room-message] p").text()).toBe(longMessage);
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("uses a compact message typography scale for dense room conversations", () => {
    const source = readFileSync("src/components/room/ConversationStream.vue", "utf8");

    expect(source).toContain("--conversation-message-font-size: 13px;");
    expect(source).toContain("--conversation-message-body-font-size: 12.5px;");
  });

  it("uses one agent message surface before and after streaming completes", () => {
    const wrapper = mount(ConversationStream, {
      props: {
        agentLabel: "证据书记官",
        messages: [
          {
            id: "MESSAGE_DURABLE",
            sequence_no: 1,
            sender_role: "CUSTOMER_SERVICE",
            message_type: "AGENT_MESSAGE",
            message_text: "已经完成的核验意见。",
          },
        ],
        streamingRuns: [
          {
            runId: "AGENT_RUN_ACTIVE",
            status: "STREAMING",
            senderRole: "EVIDENCE_CLERK",
            content: "正在继续生成核验意见。",
            activeCardKey: "default",
            cardOrder: ["default"],
            cards: {
              default: {
                key: "default",
                senderRole: "EVIDENCE_CLERK",
                content: "正在继续生成核验意见。",
              },
            },
          },
        ],
      },
    });

    expect(wrapper.classes()).toContain("conversation-stream--evidence-clerk");
    expect(wrapper.find(".conversation-stream__message--agent").exists()).toBe(true);
    expect(wrapper.find("[data-agent-streaming-message]").exists()).toBe(true);

    const conversationSource = readFileSync(
      "src/components/room/ConversationStream.vue",
      "utf8",
    );
    const streamingSource = readFileSync(
      "src/components/room/AgentStreamingMessage.vue",
      "utf8",
    );
    expect(conversationSource).toContain(
      "background: var(--conversation-agent-message-background)",
    );
    expect(streamingSource).toContain(
      "background: var(--conversation-agent-message-background, #fffaf1)",
    );
    expect(conversationSource).toContain("box-shadow: none");
    expect(streamingSource).toContain("box-shadow: none");
  });

  // 业务位置：【Java 房间协作】it：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、访问会话和参与方身份 正确进入 接待/证据回合记忆、Agent 上下文和事件。上游：房间消息、访问会话和参与方身份。下游：接待/证据回合记忆、Agent 上下文和事件。边界：会话和可见性必须按参与方隔离。
  it("automatically scrolls to the latest message when history changes", () => {
    const source = readFileSync("src/components/room/ConversationStream.vue", "utf8");

    expect(source).toContain("messagesRail");
    expect(source).toContain("scrollToLatestMessage");
    expect(source).toContain("watch([displayedMessages, pendingStreamingRuns]");
    expect(source).toContain("rail.scrollTop = rail.scrollHeight;");
  });
});
