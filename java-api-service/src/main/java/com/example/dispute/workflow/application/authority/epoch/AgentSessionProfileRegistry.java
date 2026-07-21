package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.config.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** Immutable versioned registry for the Agent Session profile identity boundary. */
public final class AgentSessionProfileRegistry {

    public static final String AGENT_KEY = "DISPUTE_INTAKE_OFFICER";
    public static final String PROFILE_VERSION = "agent-session-profile.v1";
    public static final String MEMORY_POLICY_ID = "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1";

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private AgentSessionProfileRegistry() {}

    public static String profileId(ActorRole actorRole, String promptVersion) {
        return profileId(AGENT_KEY, actorRole, promptVersion, PROFILE_VERSION);
    }

    public static String profileId(
            String agentKey,
            ActorRole actorRole,
            String promptVersion,
            String agentSessionProfileVersion) {
        ProfileHashInput input = new ProfileHashInput(
                agentKey, actorRole, promptVersion, agentSessionProfileVersion);
        return "asp.v1." + ContractJson.sha256Hex(MAPPER.valueToTree(input));
    }

    public static void requireExact(
            String promptProfileId,
            String agentKey,
            ActorRole actorRole,
            String promptVersion,
            String agentSessionProfileVersion) {
        String expected = profileId(agentKey, actorRole, promptVersion, agentSessionProfileVersion);
        if (!expected.equals(promptProfileId)) {
            throw new IllegalArgumentException(
                    "promptProfileId does not match the immutable Agent Session profile inputs");
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ProfileHashInput(
            String agentKey,
            ActorRole actorRole,
            String promptVersion,
            String agentSessionProfileVersion) {
        ProfileHashInput {
            required(agentKey, "agentKey");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
                throw new IllegalArgumentException("actorRole must be USER or MERCHANT");
            }
            required(promptVersion, "promptVersion");
            required(agentSessionProfileVersion, "agentSessionProfileVersion");
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }
}
