import { LocalStore } from "./LocalStore";
import { SyncApi } from "./SyncApi";
import { SyncEngine } from "./SyncEngine";
import { TabClaim } from "./TabClaim";

/**
 * <ul>
 * <li>idle: nothing opened (yet), or closed.</li>
 * <li>opening: claiming them from other tabs, then loading them.</li>
 * <li>active: the engine syncs them.</li>
 * <li>elsewhere: another tab holds them (see {@link NotesSession.takeOver}).</li>
 * <li>failed: they couldn't be opened (see {@link NotesSession.failure}).</li>
 * </ul>
 */
export type SessionPhase = "idle" | "opening" | "active" | "elsewhere" | "failed";

/**
 * The logged-in user's notes on this device, for every page: opened by the first page that needs
 * them, then synced while the user moves between pages, until another tab takes them over (see
 * TabClaim), another user logs in, or the user logs out.
 */
export class NotesSession {
  private username?: string;
  private currentPhase: SessionPhase = "idle";
  private failureMessage?: string;
  private claim?: TabClaim;
  private store?: LocalStore;
  private currentEngine?: SyncEngine;
  private unsubscribe?: () => void;
  /** Opening and closing one at a time, in order. */
  private queue: Promise<void> = Promise.resolve();

  /**
   * @param newApi the server, for each engine.
   * @param onChange called when the phase or the notes change.
   */
  constructor(private readonly newApi: () => SyncApi, private readonly onChange: () => void) {}

  get phase(): SessionPhase {
    return this.currentPhase;
  }

  /** Why they couldn't be opened, in the failed phase. */
  get failure(): string | undefined {
    return this.failureMessage;
  }

  /** Set from the opening phase on, while loading too: it then tells its status. */
  get engine(): SyncEngine | undefined {
    return this.currentEngine;
  }

  /**
   * Opens {@code username}'s notes, unless they are open or another tab holds them. Another user's
   * are closed first. Opened again if they failed to, or if the engine was signed out: its loops
   * stopped for good, and the user has logged in again since.
   */
  open(username: string): Promise<void> {
    return this.serialize(async () => {
      if (this.username !== username) {
        await this.close();
      } else if (this.currentPhase === "active" && this.currentEngine!.status().state === "signedOut") {
        await this.deactivate();
      } else if (this.currentPhase !== "idle" && this.currentPhase !== "failed") {
        return;
      }
      this.username = username;
      await this.activate();
    });
  }

  /** Takes the notes back from the tab holding them. */
  takeOver(): Promise<void> {
    return this.serialize(async () => {
      if (this.currentPhase === "elsewhere") await this.activate();
    });
  }

  /** Saves unsaved edits and sends pending ones now: the page may be about to go away. */
  flush(): Promise<void> {
    return this.currentEngine?.flush() ?? Promise.resolve();
  }

  /** The server may be reachable again: ends the wait before the next retry, if there's one. */
  retryIfWaiting() {
    this.currentEngine?.retryIfWaiting();
  }

  /** Closes the notes and deletes this device's copy of {@code username}'s, even if they weren't open. */
  destroy(username: string): Promise<void> {
    return this.serialize(async () => {
      await this.close();
      await LocalStore.destroy(username);
    });
  }

  private serialize(task: () => Promise<void>): Promise<void> {
    const run = this.queue.then(task);
    this.queue = run.catch(() => undefined);
    return run;
  }

  private async activate() {
    this.setPhase("opening");
    this.claim ??= new TabClaim(this.username!);
    if (!(await this.claim.claim(() => this.lose()))) {
      this.setPhase("elsewhere");
      return;
    }
    try {
      this.store = await LocalStore.open(this.username!);
      const engine = new SyncEngine(this.store, this.newApi());
      this.unsubscribe = engine.onChange(this.onChange);
      this.currentEngine = engine;
      await engine.start();
      this.setPhase("active");
    } catch (e) {
      await this.deactivate();
      this.failureMessage = e instanceof Error ? e.message : String(e);
      this.setPhase("failed");
    }
  }

  /**
   * Another tab took the notes over. Not queued: that tab waits for this one to stop, and this one
   * may be holding them while queued work waits.
   */
  private async lose() {
    await this.deactivate();
    this.setPhase("elsewhere");
  }

  private async deactivate() {
    const engine = this.currentEngine;
    this.currentEngine = undefined;
    this.unsubscribe?.();
    this.unsubscribe = undefined;
    await engine?.stop();
    this.store?.close();
    this.store = undefined;
  }

  private async close() {
    await this.deactivate();
    this.claim?.close();
    this.claim = undefined;
    this.username = undefined;
    this.setPhase("idle");
  }

  private setPhase(phase: SessionPhase) {
    this.currentPhase = phase;
    if (phase !== "failed") this.failureMessage = undefined;
    this.onChange();
  }
}
