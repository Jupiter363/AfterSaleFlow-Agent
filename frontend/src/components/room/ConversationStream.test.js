import { mount } from "@vue/test-utils";
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
});
