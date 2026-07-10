package com.example.dispute.casecore.application;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Serializes all import transactions inside one Java process. */
@Component
public class SingleInstanceImportGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T execute(Supplier<T> importAction) {
        Objects.requireNonNull(importAction, "importAction must not be null");
        lock.lock();
        try {
            return importAction.get();
        } finally {
            lock.unlock();
        }
    }
}
