package com.example.dispute.room.domain;

/** Provenance of immutable room content, independent from its display message type. */
public enum MessageSource {
    SYSTEM_STAGE_EVENT,
    ROLE_TEMPLATE,
    AGENT_LLM,
    PARTY_ACTION
}
