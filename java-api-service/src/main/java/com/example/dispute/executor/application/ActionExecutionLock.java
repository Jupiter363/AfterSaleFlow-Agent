package com.example.dispute.executor.application;

public interface ActionExecutionLock {

    String acquire(String idempotencyKey);

    void release(String idempotencyKey, String ownerToken);
}
