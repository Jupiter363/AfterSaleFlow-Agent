package com.example.dispute.room.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.dispute.room.application.CaseEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class RedisCaseEventWakeupSubscriberTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CaseEventService eventService = mock(CaseEventService.class);

    @Test
    void validHintTriggersCatchUpByCaseWithoutTrustingTheAdvertisedCursor() throws Exception {
        RedisCaseEventWakeupSubscriber subscriber =
                new RedisCaseEventWakeupSubscriber(objectMapper, eventService);
        String encoded =
                objectMapper.writeValueAsString(
                        new CaseEventWakeup(
                                CaseEventWakeup.SCHEMA_VERSION, "CASE_1", 999));

        subscriber.accept(encoded);

        verify(eventService).wakeUp("CASE_1");
    }

    @Test
    void malformedHintIsIgnored() {
        RedisCaseEventWakeupSubscriber subscriber =
                new RedisCaseEventWakeupSubscriber(objectMapper, eventService);

        subscriber.accept("{\"schema_version\":\"wrong\"}");

        verify(eventService, never()).wakeUp(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void localCatchUpFailureIsContained() throws Exception {
        doThrow(new IllegalStateException("PostgreSQL temporarily unavailable"))
                .when(eventService)
                .wakeUp("CASE_1");
        RedisCaseEventWakeupSubscriber subscriber =
                new RedisCaseEventWakeupSubscriber(objectMapper, eventService);
        String encoded =
                objectMapper.writeValueAsString(
                        new CaseEventWakeup(CaseEventWakeup.SCHEMA_VERSION, "CASE_1", 1));

        assertThatCode(() -> subscriber.accept(encoded)).doesNotThrowAnyException();
    }
}
