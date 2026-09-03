package com.example.dispute.workflow.observability;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TemporalSearchAttributes {

    public static final SearchAttributeKey<String> TENANT_SURROGATE =
            SearchAttributeKey.forKeyword("DisputeTenantSurrogate");
    public static final SearchAttributeKey<String> CASE_SURROGATE =
            SearchAttributeKey.forKeyword("DisputeCaseSurrogate");
    public static final SearchAttributeKey<String> WORKFLOW_KIND =
            SearchAttributeKey.forKeyword("DisputeWorkflowKind");
    public static final SearchAttributeKey<String> MACRO_PHASE =
            SearchAttributeKey.forKeyword("DisputeMacroPhase");
    public static final SearchAttributeKey<String> ROOM_TYPE =
            SearchAttributeKey.forKeyword("DisputeRoomType");
    public static final SearchAttributeKey<String> CONTRACT_VERSION =
            SearchAttributeKey.forKeyword("DisputeContractVersion");
    public static final SearchAttributeKey<String> TERMINAL_STATUS =
            SearchAttributeKey.forKeyword("DisputeTerminalStatus");

    private static final Set<String> ALLOWED_KEYS =
            Set.of(
                    TENANT_SURROGATE.getName(),
                    CASE_SURROGATE.getName(),
                    WORKFLOW_KIND.getName(),
                    MACRO_PHASE.getName(),
                    ROOM_TYPE.getName(),
                    CONTRACT_VERSION.getName(),
                    TERMINAL_STATUS.getName());
    private static final Pattern SURROGATE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final boolean enabled;

    @Autowired
    public TemporalSearchAttributes(AppProperties properties) {
        this(properties.temporal().observability().searchAttributesEnabled());
    }

    TemporalSearchAttributes(boolean enabled) {
        this.enabled = enabled;
    }

    public static TemporalSearchAttributes disabled() {
        return new TemporalSearchAttributes(false);
    }

    public static TemporalSearchAttributes enabled() {
        return new TemporalSearchAttributes(true);
    }

    public SearchAttributes caseProcess(CaseCommandRef command) {
        if (!enabled) {
            return SearchAttributes.EMPTY;
        }
        requireSurrogate(command.tenantSurrogate(), "tenant surrogate");
        requireSurrogate(command.caseId(), "case surrogate");
        String room = command.roomType().name();
        return SearchAttributes.newBuilder()
                .set(TENANT_SURROGATE, command.tenantSurrogate())
                .set(CASE_SURROGATE, command.caseId())
                .set(WORKFLOW_KIND, "CASE_PROCESS")
                .set(MACRO_PHASE, room)
                .set(ROOM_TYPE, room)
                .set(CONTRACT_VERSION, "case-process.v1")
                .set(TERMINAL_STATUS, "RUNNING")
                .build();
    }

    public static Set<String> allowedKeyNames() {
        return ALLOWED_KEYS;
    }

    private static void requireSurrogate(String value, String label) {
        if (value == null || !SURROGATE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a safe non-PII identifier");
        }
    }
}
