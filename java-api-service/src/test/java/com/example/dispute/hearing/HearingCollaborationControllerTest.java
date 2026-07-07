package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.TraceIdFilter;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.api.HearingCollaborationController;
import com.example.dispute.hearing.application.HearingCourtBootstrapService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.application.SettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class HearingCollaborationControllerTest {

    @Mock private HearingRoundService roundService;
    @Mock private SettlementService settlementService;
    @Mock private HearingCourtBootstrapService bootstrapService;
    @Mock private Authentication authentication;

    @Test
    void hearingEndpointBootstrapsCourtDossierBeforeReturningTheReadModel() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-08T01:00:00Z"), ZoneOffset.UTC);
        HearingCollaborationController controller =
                new HearingCollaborationController(
                        roundService, settlementService, bootstrapService, clock);
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, "REQ_HEARING");
        request.setAttribute(TraceIdFilter.TRACE_ATTRIBUTE, "TRACE_HEARING");
        when(authentication.getPrincipal()).thenReturn(actor);
        when(roundService.list("CASE_BOOTSTRAP", actor)).thenReturn(List.of());
        when(settlementService.list("CASE_BOOTSTRAP", actor)).thenReturn(List.of());

        var response = controller.hearing("CASE_BOOTSTRAP", authentication, request);

        assertThat(response.data()).isInstanceOf(Map.class);
        InOrder order = inOrder(bootstrapService, roundService, settlementService);
        order.verify(bootstrapService).bootstrap("CASE_BOOTSTRAP", actor, "TRACE_HEARING");
        order.verify(roundService).list("CASE_BOOTSTRAP", actor);
        order.verify(settlementService).list("CASE_BOOTSTRAP", actor);
    }
}
