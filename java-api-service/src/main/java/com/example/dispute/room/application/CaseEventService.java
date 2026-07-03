package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class CaseEventService {

    private static final long EMITTER_TIMEOUT_MS = 4 * 60 * 60 * 1000L;

    private final CaseTimelineEventRepository eventRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();

    public CaseEventService(
            CaseTimelineEventRepository eventRepository,
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CaseTimelineEventEntity recordRoomMessage(
            String caseId,
            String roomId,
            String messageId,
            String messageText,
            String audienceJson,
            String actorId) {
        long sequence = eventRepository.findMaxSequenceByCaseId(caseId) + 1;
        CaseTimelineEventEntity event =
                eventRepository.save(
                        CaseTimelineEventEntity.create(
                                "EVENT_" + compactUuid(),
                                caseId,
                                roomId,
                                sequence,
                                "ROOM_MESSAGE_CREATED",
                                clock.instant(),
                                json(List.of(messageId)),
                                json(Map.of("message_id", messageId, "text", nullToEmpty(messageText))),
                                audienceJson,
                                "room-message:" + messageId,
                                actorId));
        publishAfterCommit(caseId, event);
        return event;
    }

    public List<CaseEventView> replay(
            String caseId, long afterSequence, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        return eventRepository
                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        caseId, afterSequence)
                .stream()
                .filter(event -> visibleTo(event, actor.role()))
                .map(CaseEventService::view)
                .toList();
    }

    public SseEmitter subscribe(
            String caseId, long afterSequence, AuthenticatedActor actor) {
        assertCanAccess(caseId, actor);
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription =
                new Subscription(actor.role(), emitter, new AtomicLong(afterSequence));
        subscriptions.computeIfAbsent(caseId, ignored -> new CopyOnWriteArrayList<>())
                .add(subscription);
        Runnable remove = () -> remove(caseId, subscription);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        replay(caseId, afterSequence, actor)
                .forEach(event -> send(caseId, subscription, event));
        return emitter;
    }

    @Scheduled(fixedDelayString = "${dispute.sse-heartbeat-ms:15000}")
    public void heartbeat() {
        subscriptions.forEach(
                (caseId, caseSubscriptions) ->
                        caseSubscriptions.forEach(
                                subscription -> {
                                    try {
                                        subscription.emitter().send(
                                                SseEmitter.event().comment("heartbeat"));
                                    } catch (IOException failure) {
                                        remove(caseId, subscription);
                                    }
                                }));
    }

    private void publishAfterCommit(String caseId, CaseTimelineEventEntity event) {
        Runnable publish = () -> publish(caseId, event);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }

    private void publish(String caseId, CaseTimelineEventEntity event) {
        CaseEventView view = view(event);
        subscriptions.getOrDefault(caseId, new CopyOnWriteArrayList<>())
                .forEach(
                        subscription -> {
                            if (visibleTo(event, subscription.role())) {
                                send(caseId, subscription, view);
                            }
                        });
    }

    private void send(String caseId, Subscription subscription, CaseEventView event) {
        synchronized (subscription) {
            if (event.sequenceNo() <= subscription.lastSequence().get()) {
                return;
            }
            try {
                subscription.emitter().send(
                        SseEmitter.event()
                                .id(Long.toString(event.sequenceNo()))
                                .name(event.eventType())
                                .data(event));
                subscription.lastSequence().set(event.sequenceNo());
            } catch (IOException failure) {
                remove(caseId, subscription);
            }
        }
    }

    private void assertCanAccess(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        boolean privileged =
                switch (actor.role()) {
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                    default -> false;
                };
        boolean owner =
                actor.role() == ActorRole.USER && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        caseId, actor.actorId(), actor.role());
        if (!privileged && !owner && !participant) {
            throw new ForbiddenException("actor cannot subscribe to this case");
        }
    }

    private boolean visibleTo(CaseTimelineEventEntity event, ActorRole role) {
        try {
            List<String> audiences =
                    objectMapper.readValue(
                            event.getAudienceJson(), new TypeReference<>() {});
            return audiences.isEmpty() || audiences.contains(role.name());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid event audience", exception);
        }
    }

    private void remove(String caseId, Subscription subscription) {
        List<Subscription> caseSubscriptions = subscriptions.get(caseId);
        if (caseSubscriptions != null) {
            caseSubscriptions.remove(subscription);
            if (caseSubscriptions.isEmpty()) {
                subscriptions.remove(caseId);
            }
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize case event", exception);
        }
    }

    private static CaseEventView view(CaseTimelineEventEntity event) {
        return new CaseEventView(
                event.getSequenceNo(),
                event.getEventType(),
                event.getRoomId(),
                event.getEventJson(),
                event.getEventTime());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Subscription(
            ActorRole role, SseEmitter emitter, AtomicLong lastSequence) {}
}
