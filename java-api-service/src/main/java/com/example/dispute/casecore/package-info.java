/**
 * Owns the FulfillmentDisputeCase aggregate and its legal state transitions.
 *
 * <p>This boundary is the business fact source. Agent output may propose facts or decisions, but
 * it cannot mutate the aggregate without an application service validating the transition.
 */
package com.example.dispute.casecore;
