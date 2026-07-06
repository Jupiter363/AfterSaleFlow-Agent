import { mount } from "@vue/test-utils";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import ConversationStream from "./ConversationStream.vue";

describe("ConversationStream", () => {
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

    await wrapper.get("textarea").setValue("请核对签收照片。");
    await wrapper.get("[data-send-message]").trigger("submit");

    expect(wrapper.emitted("submit")?.[0]?.[0]).toEqual({
      message_type: "PARTY_TEXT",
      text: "请核对签收照片。",
      attachment_refs: [],
    });
  });

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

  it("keeps history inside a scrollable message rail", () => {
    const source = readFileSync("src/components/room/ConversationStream.vue", "utf8");

    expect(source).toContain("grid-template-rows: minmax(0, 1fr) auto;");
    expect(source).toContain("overflow-y: auto;");
  });

  it("automatically scrolls to the latest message when history changes", () => {
    const source = readFileSync("src/components/room/ConversationStream.vue", "utf8");

    expect(source).toContain("messagesRail");
    expect(source).toContain("scrollToLatestMessage");
    expect(source).toContain("watch(orderedMessages");
    expect(source).toContain("rail.scrollTop = rail.scrollHeight;");
  });
});
