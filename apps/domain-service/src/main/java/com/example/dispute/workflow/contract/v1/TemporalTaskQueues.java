package com.example.dispute.workflow.contract.v1;

import java.util.List;

public final class TemporalTaskQueues {

    public static final String CASE_CONTROL = "case-control";
    public static final String ROOM_CONTROL = "room-control";
    public static final String AGENT_EXECUTION = "agent-execution";
    public static final String NOTIFICATION_AND_TOOLS = "notification-and-tools";

    private static final List<String> ALL =
            List.of(CASE_CONTROL, ROOM_CONTROL, AGENT_EXECUTION, NOTIFICATION_AND_TOOLS);

    private TemporalTaskQueues() {}

    public static List<String> all() {
        return ALL;
    }
}
