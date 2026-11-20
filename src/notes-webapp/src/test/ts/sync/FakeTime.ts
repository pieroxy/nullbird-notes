import { sha256Bytes } from "../../../ts/sync/Sha256";

/**
 * Fake time for the sync engine, which also waits on work fake timers don't drive:
 * <ul>
 * <li>fake-indexeddb works on real setImmediate, one step at a time. Advancing fake timers yields
 * to the event loop only briefly, and on a busy machine (test files run in parallel) lets just
 * about one of those steps through: each step here also lets through a fixed number of them.</li>
 * <li>crypto.subtle.digest runs on Node's thread pool, in real time: see {@link useInstantDigest}.</li>
 * </ul>
 * That way, a test's budget of fake time doesn't depend on the load.
 */

/** Node's: the test tsconfig leaves out Node's types. */
declare function setImmediate(callback: (value?: unknown) => void): unknown;

/** setImmediate rounds per step: a few IndexedDB requests and their transaction. */
const IMMEDIATES_PER_STEP = 20;
const STEP_MILLIS = 50;

async function step(millis: number) {
  await jest.advanceTimersByTimeAsync(millis);
  for (let i = 0; i < IMMEDIATES_PER_STEP; i++) await new Promise((resolve) => setImmediate(resolve));
}

/** Advances fake time in small steps until {@code condition} holds. */
export async function until(condition: () => boolean, maxMillis = 120_000) {
  for (let elapsed = 0; elapsed <= maxMillis; elapsed += STEP_MILLIS) {
    if (condition()) return;
    await step(STEP_MILLIS);
  }
  throw new Error("Condition not met within " + maxMillis + " ms");
}

/** Advances fake time in small steps. */
export async function advance(millis: number) {
  for (let elapsed = 0; elapsed < millis; elapsed += STEP_MILLIS) await step(Math.min(STEP_MILLIS, millis - elapsed));
}

/**
 * Makes crypto.subtle.digest compute SHA-256 at once, with the pure TS implementation (checked
 * against it in Sha256.test.ts). Restored by jest.restoreAllMocks().
 */
export function useInstantDigest() {
  jest.spyOn(crypto.subtle, "digest").mockImplementation(async (_algorithm, data) => {
    const view = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data as ArrayBuffer);
    const hex = sha256Bytes(view);
    return Uint8Array.from(hex.match(/../g)!, (byte) => parseInt(byte, 16)).buffer;
  });
}
