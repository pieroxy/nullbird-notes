/**
 * Keeps a user's notes active in one tab only: two sync engines on one local store would overwrite
 * each other's edits. The newest tab claims them; the tab that held them saves, stops, confirms,
 * and is told it lost them. Uses BroadcastChannel, which (unlike the Web Locks API) also exists on
 * pages served over plain HTTP.
 */
export class TabClaim {
  /** How long to wait for the tab holding the notes to let go, if it doesn't answer (closed, frozen). */
  static readonly RELEASE_TIMEOUT = 1_000;

  private readonly channel: BroadcastChannel;
  private readonly id = Math.random().toString(36).slice(2);
  private claimedAt = 0;
  private active = false;
  private onLost?: () => Promise<void>;
  private released?: () => void;
  private yieldedWhileClaiming = false;

  constructor(username: string) {
    this.channel = new BroadcastChannel("notes-tab-" + username);
    this.channel.onmessage = (event) => void this.receive(event.data);
  }

  /**
   * Takes the notes over from any other tab, once that tab has stopped.
   *
   * @param onLost called when another tab takes them over: must stop using them before it resolves.
   * @return false if another tab claimed them at the same time and won: this one must not use them.
   */
  async claim(onLost: () => Promise<void>): Promise<boolean> {
    this.onLost = onLost;
    this.claimedAt = Date.now();
    this.yieldedWhileClaiming = false;
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, TabClaim.RELEASE_TIMEOUT);
      this.released = () => {
        clearTimeout(timer);
        resolve();
      };
      this.channel.postMessage({ type: "claim", from: this.id, at: this.claimedAt });
    });
    this.released = undefined;
    this.active = !this.yieldedWhileClaiming;
    return this.active;
  }

  close() {
    this.channel.close();
  }

  private async receive(message: { type: string; from: string; at: number }) {
    if (message.from === this.id) return;
    if (message.type === "released") {
      this.released?.();
    } else if (message.type === "claim" && (this.active || this.released) && this.yields(message)) {
      if (this.released) {
        this.yieldedWhileClaiming = true;
      } else {
        this.active = false;
        await this.onLost?.();
      }
      this.released?.();
      this.released = undefined;
      this.channel.postMessage({ type: "released", from: this.id, at: Date.now() });
    }
  }

  /** The later claim wins; on a tie, the larger id, so that exactly one of two simultaneous tabs keeps them. */
  private yields(other: { from: string; at: number }): boolean {
    return other.at > this.claimedAt || (other.at === this.claimedAt && other.from > this.id);
  }
}
