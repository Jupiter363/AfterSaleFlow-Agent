package com.example.dispute.common.transaction;

import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PostCommitSideEffectExecutor {

    static final String EXECUTOR_BEAN_NAME = "postCommitSideEffectTaskExecutor";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PostCommitSideEffectExecutor.class);

    private final Executor sideEffectExecutor;

    public PostCommitSideEffectExecutor(
            @Qualifier(EXECUTOR_BEAN_NAME) Executor sideEffectExecutor) {
        this.sideEffectExecutor = sideEffectExecutor;
    }

    public void execute(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        Runnable dispatch = () -> dispatch(operation, context, action);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch.run();
                    }
                });
    }

    private void dispatch(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        try {
            sideEffectExecutor.execute(() -> runGuarded(operation, context, action));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Post-commit side effect dispatch failed: operation={} context={} exception_type={} message={}",
                    operation,
                    context == null ? Map.of() : context,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
        }
    }

    private void runGuarded(
            String operation,
            Map<String, ?> context,
            Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Post-commit side effect failed: operation={} context={} exception_type={} message={}",
                    operation,
                    context == null ? Map.of() : context,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
        }
    }
}
