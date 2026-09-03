package com.example.dispute.agentstream.application;

/** Typed failure contract propagated from a room-owned formal result committer. */
public interface AgentRunFinalizationFailure {

    String code();

    boolean retryable();
}
