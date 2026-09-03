package com.example.dispute.workflow.activity.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Cooperative cancellation signal shared by heartbeat handling and the open Python stream. */
public final class AgentRunCancellationToken {

    private final List<Runnable> listeners = new ArrayList<>();
    private RuntimeException terminationCause;

    public Registration onCancellation(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean runImmediately;
        synchronized (this) {
            runImmediately = terminationCause != null;
            if (!runImmediately) {
                listeners.add(listener);
            }
        }
        if (runImmediately) {
            listener.run();
        }
        return () -> remove(listener);
    }

    public synchronized boolean isCancellationRequested() {
        return terminationCause != null;
    }

    public synchronized RuntimeException terminationCause() {
        return terminationCause;
    }

    public void throwIfCancellationRequested() {
        RuntimeException cause = terminationCause();
        if (cause != null) {
            throw cause;
        }
    }

    void requestCancellation(RuntimeException cause) {
        Objects.requireNonNull(cause, "cause");
        List<Runnable> callbacks;
        synchronized (this) {
            if (terminationCause != null) {
                return;
            }
            terminationCause = cause;
            callbacks = List.copyOf(listeners);
            listeners.clear();
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                cause.addSuppressed(callbackFailure);
            }
        }
    }

    private synchronized void remove(Runnable listener) {
        listeners.remove(listener);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
