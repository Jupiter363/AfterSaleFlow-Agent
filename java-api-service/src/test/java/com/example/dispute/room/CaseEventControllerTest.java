package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.api.CaseEventController;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.CaseEventView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CaseEventControllerTest {

    @Mock private CaseEventService service;
    @Mock private Authentication authentication;

    @Test
    void replayEndpointReturnsDurableCaseEventsForLedgerRebuild() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);
        CaseEventController controller = new CaseEventController(service, clock);
        AuthenticatedActor actor = new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_EVENTS_REPLAY");
        request.setAttribute(TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_EVENTS_REPLAY");
        CaseEventView event =
                new CaseEventView(
                        12,
                        "EXECUTION_ASSISTANT_HANDOFF",
                        null,
                        "{\"status\":\"EXECUTION_ASSISTANT_HANDOFF\"}",
                        Instant.parse("2026-07-10T00:00:00Z"));
        when(authentication.getPrincipal()).thenReturn(actor);
        when(service.replay("CASE_LEDGER", 3, actor)).thenReturn(List.of(event));

        var response = controller.replay("CASE_LEDGER", 3, authentication, request);

        assertThat(response.success()).isTrue();
        assertThat(response.requestId()).isEqualTo("REQ_EVENTS_REPLAY");
        assertThat(response.traceId()).isEqualTo("TRACE_EVENTS_REPLAY");
        assertThat(response.data()).containsExactly(event);
    }
}
