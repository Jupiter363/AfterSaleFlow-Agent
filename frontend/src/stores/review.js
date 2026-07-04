import { reactive } from "vue";
import { reviewApi } from "../api/review";
import { createResourceState, loadResource } from "./resource";

export const reviewStore = reactive({
  queue: createResourceState([]),
  packet: createResourceState(null),
  decisionPending: false,
});

export function loadReviews(actor, status = "PENDING") {
  return loadResource(reviewStore.queue, () => reviewApi.list(actor, status));
}

export function loadReviewPacket(actor, reviewId) {
  return loadResource(reviewStore.packet, () => reviewApi.packet(actor, reviewId));
}
