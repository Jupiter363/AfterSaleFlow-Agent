import { reactive } from "vue";
import {
  ACTIVE_REVIEW_STATUSES,
  mergeActiveReviewTasks,
  normalizeReviewPacket,
  reviewApi,
  toReviewTaskSummary,
} from "../api/review";
import { createResourceState, loadResource } from "./resource";

export const reviewStore = reactive({
  queue: createResourceState([]),
  packet: createResourceState(null),
  decisionPending: false,
});

export function loadReviews(actor, status = "PENDING") {
  return loadResource(reviewStore.queue, async () =>
    (await reviewApi.list(actor, status)).map(toReviewTaskSummary),
  );
}

export function loadActiveReviews(actor) {
  return loadResource(reviewStore.queue, async () => {
    const groups = await Promise.all(
      ACTIVE_REVIEW_STATUSES.filter((status) => status !== "ASSIGNED").map(
        (status) => reviewApi.list(actor, status),
      ),
    );
    return mergeActiveReviewTasks(groups);
  });
}

export function loadReviewPacket(actor, reviewId) {
  return loadResource(reviewStore.packet, async () =>
    normalizeReviewPacket(await reviewApi.packet(actor, reviewId)),
  );
}
