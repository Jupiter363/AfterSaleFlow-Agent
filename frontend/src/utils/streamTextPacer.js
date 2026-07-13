// 文件作用：将已经从服务端收到的流式文本按浏览器绘制帧渐进展示。
// 边界：只调度已收到的文本，不预测、不补写、不修改服务端内容。

const FRAME_DURATION_MS = 16;
const DEFAULT_TARGET_CATCH_UP_MS = 1000;
const DEFAULT_MAX_CHARACTERS_PER_FRAME = 2048;

function shouldRevealImmediately() {
  if (typeof document !== "undefined" && document.visibilityState === "hidden") {
    return true;
  }
  return Boolean(
    globalThis.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches,
  );
}

function scheduleVisualFrame(callback) {
  if (
    typeof globalThis.requestAnimationFrame === "function" &&
    !shouldRevealImmediately()
  ) {
    const token = {
      kind: "hybrid-frame",
      animationHandle: null,
      timeoutHandle: null,
      completed: false,
    };
    const deliver = () => {
      if (token.completed) return;
      token.completed = true;
      if (token.animationHandle !== null) {
        globalThis.cancelAnimationFrame?.(token.animationHandle);
      }
      if (token.timeoutHandle !== null) {
        globalThis.clearTimeout(token.timeoutHandle);
      }
      callback();
    };
    token.animationHandle = globalThis.requestAnimationFrame(deliver);
    // requestAnimationFrame can be suspended when the tab becomes hidden.
    // The watchdog keeps finalization and cleanup from waiting indefinitely.
    token.timeoutHandle = globalThis.setTimeout(deliver, 100);
    return token;
  }
  return {
    kind: "timeout",
    handle: globalThis.setTimeout(callback, FRAME_DURATION_MS),
  };
}

function cancelVisualFrame(token) {
  if (!token) return;
  if (token.kind === "hybrid-frame") {
    token.completed = true;
    if (token.animationHandle !== null) {
      globalThis.cancelAnimationFrame?.(token.animationHandle);
    }
    if (token.timeoutHandle !== null) {
      globalThis.clearTimeout(token.timeoutHandle);
    }
    return;
  }
  if (
    token.kind === "animation-frame" &&
    typeof globalThis.cancelAnimationFrame === "function"
  ) {
    globalThis.cancelAnimationFrame(token.handle);
    return;
  }
  globalThis.clearTimeout(token.handle);
}

function codePointLength(value) {
  const text = String(value || "");
  let offset = 0;
  let length = 0;
  while (offset < text.length) {
    const codePoint = text.codePointAt(offset);
    offset += codePoint > 0xffff ? 2 : 1;
    length += 1;
  }
  return length;
}

function utf16OffsetForCodePoints(value, count) {
  let offset = 0;
  let consumed = 0;
  while (offset < value.length && consumed < count) {
    const codePoint = value.codePointAt(offset);
    offset += codePoint > 0xffff ? 2 : 1;
    consumed += 1;
  }
  return offset;
}

/**
 * Creates an adaptive display queue for streamed text.
 *
 * Small provider deltas are revealed one Unicode character per paint. A large,
 * already-received delta is drained faster so a coalesced network response does
 * not add an unbounded artificial delay. `drain()` resolves one visual frame
 * after the last reveal, allowing a durable message to replace the temporary
 * bubble without skipping its final painted state.
 */
export function createStreamTextPacer({
  onReveal,
  targetCatchUpMs = DEFAULT_TARGET_CATCH_UP_MS,
  maxCharactersPerFrame = DEFAULT_MAX_CHARACTERS_PER_FRAME,
  scheduleFrame = scheduleVisualFrame,
  cancelFrame = cancelVisualFrame,
} = {}) {
  if (typeof onReveal !== "function") {
    throw new TypeError("createStreamTextPacer requires an onReveal callback");
  }

  const queue = [];
  const drainWaiters = [];
  const targetFrames = Math.max(
    1,
    Math.round(Number(targetCatchUpMs || 0) / FRAME_DURATION_MS),
  );
  const maxPerFrame = Math.max(1, Number(maxCharactersPerFrame) || 1);
  let pendingCharacters = 0;
  let charactersPerFrame = 1;
  let frameToken = null;
  let settleToken = null;
  let stopped = false;

  function resolveDrainWaiters() {
    const waiters = drainWaiters.splice(0);
    waiters.forEach(({ resolve }) => resolve());
  }

  function rejectDrainWaiters(error) {
    const waiters = drainWaiters.splice(0);
    waiters.forEach(({ reject }) => reject(error));
  }

  function scheduleSettledFrame() {
    if (stopped || pendingCharacters > 0 || frameToken || settleToken) return;
    settleToken = scheduleFrame(() => {
      settleToken = null;
      if (pendingCharacters > 0 || frameToken) {
        scheduleTick();
        return;
      }
      resolveDrainWaiters();
    });
  }

  function revealFrame() {
    frameToken = null;
    if (stopped || pendingCharacters <= 0) {
      scheduleSettledFrame();
      return;
    }

    const charactersThisFrame = shouldRevealImmediately()
      ? pendingCharacters
      : Math.min(pendingCharacters, charactersPerFrame);
    let remainingBudget = charactersThisFrame;

    try {
      while (remainingBudget > 0 && queue.length) {
        const item = queue[0];
        const takeCount = Math.min(remainingBudget, item.characters);
        const splitAt = utf16OffsetForCodePoints(item.text, takeCount);
        const fragment = item.text.slice(0, splitAt);
        item.text = item.text.slice(splitAt);
        item.characters -= takeCount;
        pendingCharacters -= takeCount;
        remainingBudget -= takeCount;
        onReveal(item.fieldPath, fragment);
        if (item.characters <= 0) queue.shift();
      }
    } catch (failure) {
      stopped = true;
      queue.length = 0;
      pendingCharacters = 0;
      rejectDrainWaiters(failure);
      return;
    }

    if (pendingCharacters > 0) scheduleTick();
    else {
      charactersPerFrame = 1;
      scheduleSettledFrame();
    }
  }

  function scheduleTick() {
    if (stopped || frameToken || pendingCharacters <= 0) return;
    if (settleToken) {
      cancelFrame(settleToken);
      settleToken = null;
    }
    frameToken = scheduleFrame(revealFrame);
  }

  function enqueue(fieldPath, value) {
    const text = String(value || "");
    if (stopped || !text) return;
    const characters = codePointLength(text);
    const lastItem = queue.at(-1);
    if (lastItem?.fieldPath === fieldPath) {
      lastItem.text += text;
      lastItem.characters += characters;
    } else {
      queue.push({ fieldPath, text, characters });
    }
    pendingCharacters += characters;
    charactersPerFrame = Math.min(
      maxPerFrame,
      Math.max(
        charactersPerFrame,
        Math.ceil(pendingCharacters / targetFrames),
      ),
    );
    scheduleTick();
  }

  function drain() {
    if (stopped) return Promise.resolve();
    return new Promise((resolve, reject) => {
      drainWaiters.push({ resolve, reject });
      if (pendingCharacters > 0) scheduleTick();
      else scheduleSettledFrame();
    });
  }

  function cancel() {
    stopped = true;
    queue.length = 0;
    pendingCharacters = 0;
    charactersPerFrame = 1;
    cancelFrame(frameToken);
    cancelFrame(settleToken);
    frameToken = null;
    settleToken = null;
    resolveDrainWaiters();
  }

  return {
    enqueue,
    drain,
    cancel,
    get pendingCharacters() {
      return pendingCharacters;
    },
  };
}
