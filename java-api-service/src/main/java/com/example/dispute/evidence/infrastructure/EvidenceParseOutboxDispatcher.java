package com.example.dispute.evidence.infrastructure;

import com.example.dispute.evidence.application.EvidenceParseOutboxService;
import com.example.dispute.evidence.application.EvidenceStorage;
import com.example.dispute.evidence.application.EvidenceTextContentInvalidException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Recovers a durable stored-text job and never delegates supported text decoding to OCR. */
@Component
public final class EvidenceParseOutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvidenceParseOutboxDispatcher.class);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final int BATCH_SIZE = 16;

    private final EvidenceParseOutboxService outbox;
    private final EvidenceStorage storage;

    public EvidenceParseOutboxDispatcher(EvidenceParseOutboxService outbox, EvidenceStorage storage) {
        this.outbox = outbox;
        this.storage = storage;
    }

    public boolean dispatchNow(String outboxId) {
        Optional<EvidenceParseOutboxService.ClaimedJob> claimed = outbox.claimById(outboxId, LEASE_DURATION);
        if (claimed.isEmpty()) {
            return false;
        }
        deliver(claimed.orElseThrow());
        return true;
    }

    public int dispatchAvailable() {
        int count = 0;
        while (count < BATCH_SIZE) {
            Optional<EvidenceParseOutboxService.ClaimedJob> claimed = outbox.claimNext(LEASE_DURATION);
            if (claimed.isEmpty()) {
                return count;
            }
            deliver(claimed.orElseThrow());
            count++;
        }
        return count;
    }

    private void deliver(EvidenceParseOutboxService.ClaimedJob job) {
        try {
            outbox.completeStoredText(
                    job, storage.loadOriginal(job.sourceBucket(), job.sourceObjectKey()));
        } catch (EvidenceTextContentInvalidException failure) {
            boolean persisted =
                    outbox.failStoredText(
                            job, "EVIDENCE_TEXT_CONTENT_INVALID", failure.getClass().getSimpleName());
            if (!persisted) {
                LOGGER.warn("Evidence text parse terminalization lost lease: outbox_id={}", job.outboxId());
            }
        } catch (RuntimeException failure) {
            boolean persisted =
                    outbox.defer(
                            job,
                            "EVIDENCE_TEXT_DELIVERY_FAILED",
                            failure.getClass().getSimpleName());
            if (!persisted) {
                LOGGER.warn("Evidence text parse delivery lost lease: outbox_id={}", job.outboxId());
            }
        }
    }
}
