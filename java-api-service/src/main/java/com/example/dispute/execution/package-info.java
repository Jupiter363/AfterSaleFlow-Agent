/**
 * Executes only strongly typed actions that match a valid human approval snapshot.
 *
 * <p>Idempotency, action hashes, reviewer authority, and external result reconciliation are
 * security invariants of this boundary.
 */
package com.example.dispute.execution;
