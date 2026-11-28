package net.pieroxy.notes.services;

import net.pieroxy.notes.api.metadata.InvalidInputException;
import net.pieroxy.notes.storage.ChangeRecord;
import net.pieroxy.notes.storage.MetaWrite;
import net.pieroxy.notes.storage.NoteHistory;
import net.pieroxy.notes.storage.NoteMeta;
import net.pieroxy.notes.storage.NoteOptions;
import net.pieroxy.notes.storage.NoteWrite;
import net.pieroxy.notes.storage.Revision;
import net.pieroxy.notes.storage.StaleRevisionException;
import net.pieroxy.notes.storage.UserNoteStore;
import net.pieroxy.notes.sync.ChangesPage;
import net.pieroxy.notes.sync.Merge3;
import net.pieroxy.notes.sync.MetaChange;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.NoteContent;
import net.pieroxy.notes.sync.NoteInfo;
import net.pieroxy.notes.sync.NoteRequest;
import net.pieroxy.notes.sync.NotesResult;
import net.pieroxy.notes.sync.PushResult;
import net.pieroxy.notes.sync.TagOrder;
import net.pieroxy.notes.sync.TagOrderChange;
import net.pieroxy.notes.sync.TagOrderResult;
import net.pieroxy.notes.sync.TagOrderStatus;
import net.pieroxy.notes.sync.PushStatus;
import net.pieroxy.notes.sync.RejectReason;
import net.pieroxy.notes.sync.Splice;
import net.pieroxy.notes.sync.TagEvent;
import net.pieroxy.notes.sync.NoteDefaults;
import net.pieroxy.notes.sync.TagOptions;
import net.pieroxy.notes.sync.TagPaths;
import net.pieroxy.notes.sync.TagOptionsChange;
import net.pieroxy.notes.utils.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * The sync rules, on top of each user's {@link UserNoteStore}. A push is decided and committed
 * while holding the user's store lock, so that no other change of that user lands in between.
 * <p>
 * A push's text is merged with what happened since the text it derives from (its ancestor), always
 * (see {@link Merge3}). Edits beat deletes, both ways. Nothing is ever dropped.
 * <p>
 * Tags and options change on their own (see {@link MetaChange}), never conflicting with the text.
 * <p>
 * A published note has a public link: a random token, kept as its {@link NoteOptions#PUBLISHED}
 * option, which only {@link #publish} and {@link #unpublish} change.
 */
public class NoteService implements Service {
  private final static Logger LOGGER = Logger.getLogger(NoteService.class.getName());
  static final int CHANGES_PAGE_SIZE = 500;
  static final int MAX_NOTES_PER_REQUEST = 200;
  private final static int MAX_ID_LENGTH = 64;
  static final int MAX_TAGS = 100;
  /** A level of a nested tag (see TagPaths): "acme" in "work/clients/acme". */
  static final int MAX_TAG_LENGTH = 64;
  /** A whole nested tag. */
  static final int MAX_TAG_PATH_LENGTH = 255;
  static final String INVALID_TAG = "Invalid tag: tags are 1 to " + MAX_TAG_LENGTH + " characters per level (" + MAX_TAG_PATH_LENGTH
      + " in all), without whitespace or control characters.";
  /** Far more tags than anyone has: a bound on a request's size. */
  static final int MAX_ORDERED_TAGS = 10_000;
  static final int MAX_OPTIONS = 32;
  static final int MAX_OPTION_VALUE_LENGTH = 256;
  private static final Pattern OPTION_KEY_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,64}");
  /** 128 random bits, base64url. */
  private static final String PUBLISHED_BY_SERVER = "The " + NoteOptions.PUBLISHED + " option changes by publishing the note.";
  public static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{22}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final int changesPageSize;
  private SettingsService settings;
  private NoteStoreService noteStores;
  private ScheduledExecutorService timer;
  /**
   * Where each published note is, by token; built on the first lookup (see {@link #findPublished}).
   * Its own lock, never held while taking a store's: building it takes them.
   */
  private final Object publishedLock = new Object();
  private Map<String, PublishedRef> published;

  private record PublishedRef(String username, String noteId) {}

  /**
   * A published note, as it is now: its options as they apply, its own, its tags' or the user's
   * defaults (see {@link TagOptions#effective}).
   */
  public record PublishedNote(String username, NoteMeta meta, String content, boolean markdown, boolean monospace, boolean nowrap) {}

  public NoteService() {
    this(CHANGES_PAGE_SIZE);
  }

  NoteService(int changesPageSize) {
    this.changesPageSize = changesPageSize;
  }

  @Override
  public List<Class<? extends Service>> getDependencies() {
    return List.of(SettingsService.class, NoteStoreService.class);
  }

  @Override
  public void init(IServiceProvider serviceProvider) {
    settings = serviceProvider.getSettingsService();
    noteStores = serviceProvider.getNoteStoreService();
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "long-poll-timer");
      t.setDaemon(true);
      return t;
    });
  }

  @Override
  public void destroy() {
    if (timer != null) timer.shutdownNow();
  }

  public PushResult push(String username, String deviceId, NoteChange change) throws IOException {
    String invalid = validate(deviceId, change);
    if (invalid != null) return rejected(change, RejectReason.INVALID, invalid);
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      ChangeRecord done = store.findChange(change.changeId());
      if (done != null) return replay(store, change, done);
      NoteMeta head = store.get(change.noteId());
      String ancestor = ancestor(store, change, head);
      return change.deleted() ? delete(store, deviceId, change, head, ancestor) : edit(store, username, deviceId, change, head, ancestor);
    }
  }

  /**
   * Gives the note a public link, or a new one replacing it with {@code newLink}.
   *
   * @return its token; null if there's no such note, or it's deleted.
   */
  public String publish(String username, String noteId, boolean newLink) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    String old;
    String token;
    synchronized (store) {
      NoteMeta head = store.get(noteId);
      if (head == null || head.deleted()) return null;
      old = head.options().get(NoteOptions.PUBLISHED);
      if (old != null && !newLink) return old;
      token = newToken();
      store.commitMeta(noteId, new MetaWrite(null, null, null, null, Map.of(NoteOptions.PUBLISHED, token), null));
    }
    synchronized (publishedLock) {
      if (published != null) {
        if (old != null) published.remove(old);
        published.put(token, new PublishedRef(username, noteId));
      }
    }
    return token;
  }

  /** Its link stops working. @return false if there's no such note. */
  public boolean unpublish(String username, String noteId) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    String old;
    synchronized (store) {
      NoteMeta head = store.get(noteId);
      if (head == null) return false;
      old = head.options().get(NoteOptions.PUBLISHED);
      if (old == null) return true;
      store.commitMeta(noteId, new MetaWrite(null, null, null, null, null, List.of(NoteOptions.PUBLISHED)));
    }
    synchronized (publishedLock) {
      if (published != null) published.remove(old);
    }
    return true;
  }

  /**
   * The note published under {@code token}, checked against the note as it is now: the index may
   * still have a deleted note. Whether its user still exists is the caller's to check.
   *
   * @return null if there's none.
   */
  public PublishedNote findPublished(String token) throws IOException {
    if (token == null || !TOKEN_PATTERN.matcher(token).matches()) return null;
    PublishedRef ref;
    synchronized (publishedLock) {
      if (published == null) published = indexPublished();
      ref = published.get(token);
    }
    if (ref == null) return null;
    UserNoteStore store = noteStores.getStore(ref.username());
    synchronized (store) {
      NoteMeta meta = store.get(ref.noteId());
      if (meta == null || meta.deleted() || !token.equals(meta.options().get(NoteOptions.PUBLISHED))) return null;
      Map<String, String> own = meta.options();
      NoteDefaults defaults = store.noteDefaults();
      boolean nowrap = TagOptions.effective(own.get(NoteOptions.NOWRAP), tagValuesInOrder(store, meta.tags(), o -> o.wrap() == null ? null : !o.wrap()),
          defaults.isNowrap());
      return new PublishedNote(ref.username(), meta, store.readContent(meta.id()),
          TagOptions.effective(own.get(NoteOptions.MARKDOWN), tagValuesInOrder(store, meta.tags(), TagOptions::markdown), defaults.isMarkdown()),
          TagOptions.effective(own.get(NoteOptions.MONOSPACE), tagValuesInOrder(store, meta.tags(), TagOptions::monospace), defaults.isMonospace()),
          nowrap);
    }
  }

  /** Every user's published notes. A user whose notes can't be read is skipped, logged. */
  private Map<String, PublishedRef> indexPublished() {
    Map<String, PublishedRef> index = new HashMap<>();
    for (String username : noteStores.usernamesWithData()) {
      try {
        for (NoteMeta meta : noteStores.getStore(username).list()) {
          String token = meta.options().get(NoteOptions.PUBLISHED);
          if (token != null && !meta.deleted()) index.put(token, new PublishedRef(username, meta.id()));
        }
      } catch (IOException | RuntimeException e) {
        LOGGER.warning("Could not list the published notes of " + username + ": " + e.getMessage());
      }
    }
    return index;
  }

  private static String newToken() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * What each of {@code tags} says for an option, in the user's tag order (alphabetical without one,
   * as the clients); null for one saying nothing. A tag says what it sets, or else what the nearest
   * tag above it sets (see TagPaths): "work/clients" follows "work" unless it sets its own. Tags above
   * another of the note's say nothing more: "work/clients" decides, not "work" next to it.
   */
  private static List<Boolean> tagValuesInOrder(UserNoteStore store, List<String> tags, java.util.function.Function<TagOptions, Boolean> pick) {
    UserNoteStore.StoredTagOrder order = store.getTagOrder();
    List<String> deciding = TagPaths.withoutRedundant(tags);
    List<String> tree = TagPaths.treeOrder(deciding, order == null ? null : order.tags(),
        String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
    UserNoteStore.StoredTagOptions options = store.getTagOptions();
    List<Boolean> values = new ArrayList<>();
    for (String tag : tree) {
      if (!deciding.contains(tag)) continue;
      Boolean value = null;
      List<String> chain = new ArrayList<>(TagPaths.ancestors(tag));
      chain.add(tag);
      for (int i = chain.size() - 1; i >= 0 && value == null && options != null; i--) {
        TagOptions own = options.tags().get(chain.get(i));
        if (own != null) value = pick.apply(own);
      }
      values.add(value);
    }
    return values;
  }

  public PushResult pushMeta(String username, String deviceId, MetaChange change) throws IOException {
    return pushMeta(username, deviceId, change, null);
  }

  /**
   * A metadata change to a deleted note is accepted, and ignored.
   *
   * @param knownSeq the client's cursor: tags renamed or removed after it (see {@link #renameTag})
   *                 are so in the change too, made before the client knew. Null: none are.
   */
  public PushResult pushMeta(String username, String deviceId, MetaChange change, Long knownSeq) throws IOException {
    // "/work//clients" is "work/clients": added as such. Those removed are as the note has them.
    if (change.addTags() != null) {
      change = new MetaChange(change.changeId(), change.noteId(),
          change.addTags().stream().map(tag -> tag == null ? null : TagPaths.normalize(tag)).distinct().toList(),
          change.removeTags(), change.setOptions(), change.unsetOptions());
    }
    String invalid = validate(deviceId, change);
    if (invalid != null) return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, invalid);
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      if (knownSeq != null) change = afterTagEvents(change, store.tagEventsAfter(knownSeq));
      NoteMeta head = store.get(change.noteId());
      if (head == null) return rejected(change.changeId(), change.noteId(), RejectReason.UNKNOWN_NOTE, "No such note.");
      if (head.deleted() || change.noteId().equals(store.findMetaChange(change.changeId()))) return applied(change.changeId(), head);
      MetaWrite write = new MetaWrite(change.changeId(), deviceId, change.addTags(), change.removeTags(), change.setOptions(),
          change.unsetOptions());
      List<String> tags = UserNoteStore.tagsAfter(head, write);
      Map<String, String> options = UserNoteStore.optionsAfter(head, write);
      if (tags.size() > MAX_TAGS) return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, "At most " + MAX_TAGS + " tags.");
      if (options.size() > MAX_OPTIONS) {
        return rejected(change.changeId(), change.noteId(), RejectReason.INVALID, "At most " + MAX_OPTIONS + " options.");
      }
      if (tags.equals(head.tags()) && options.equals(head.options())) return applied(change.changeId(), head);
      return applied(change.changeId(), store.commitMeta(head.id(), write));
    }
  }

  /** {@code change} with each event applied to its tags, in order. */
  static MetaChange afterTagEvents(MetaChange change, List<UserNoteStore.StoredTagEvent> events) {
    if (events.isEmpty()) return change;
    List<String> add = change.addTags() == null ? null : new ArrayList<>(change.addTags());
    List<String> remove = change.removeTags() == null ? null : new ArrayList<>(change.removeTags());
    for (UserNoteStore.StoredTagEvent event : events) {
      add = renamed(add, event.from(), event.to());
      // Removing a tag that's gone already: nothing to do.
      remove = renamed(remove, event.from(), event.to());
    }
    return new MetaChange(change.changeId(), change.noteId(), add, remove, change.setOptions(), change.unsetOptions());
  }

  private static List<String> renamed(List<String> tags, String from, String to) {
    if (tags == null || !tags.contains(from)) return tags;
    List<String> result = new ArrayList<>();
    for (String tag : tags) {
      String now = tag.equals(from) ? to : tag;
      if (now != null && !result.contains(now)) result.add(now);
    }
    return result;
  }

  /**
   * Renames {@code from} to {@code to} on every note having it, deleted ones included: they'd come
   * back with the old name if restored. The tags under it go along: "work/clients" becomes
   * "job/clients" (see TagPaths). When a new name is in use already, the two merge. The tag order
   * keeps each one's place, or the target's when merged. Recorded as {@link TagEvent}s, one per tag
   * renamed, which clients get with the changes, so that their changes not sent yet follow.
   *
   * @param to normalized first (see TagPaths.normalize).
   * @return the IDs of the notes changed.
   * @throws InvalidInputException if a name isn't a valid tag, or {@code to} is under {@code from}.
   */
  public List<String> renameTag(String username, String deviceId, String from, String to) throws IOException {
    to = TagPaths.normalize(to);
    if (!isTagName(from) || !isValidTag(to)) throw new InvalidInputException(INVALID_TAG);
    if (from.equals(to)) return List.of();
    if (TagPaths.isUnder(to, from)) throw new InvalidInputException("A tag can't move under itself.");
    return retag(username, deviceId, from, to);
  }

  /**
   * Removes {@code tag} from every note having it, deleted ones included, and the tags under it,
   * likewise recorded.
   *
   * @return the IDs of the notes changed.
   */
  public List<String> deleteTag(String username, String deviceId, String tag) throws IOException {
    if (!isTagName(tag)) throw new InvalidInputException("Invalid tag.");
    return retag(username, deviceId, tag, null);
  }

  private List<String> retag(String username, String deviceId, String from, String to) throws IOException {
    if (deviceId != null && deviceId.length() > MAX_ID_LENGTH) throw new InvalidInputException("Invalid deviceId.");
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      UserNoteStore.StoredTagOrder before = store.getTagOrder();
      UserNoteStore.StoredTagOptions options = store.getTagOptions();
      // Every name the server knows at or under it: on notes, in the order, with options.
      Set<String> names = new LinkedHashSet<>();
      for (NoteMeta note : store.list()) names.addAll(note.tags());
      if (before != null) names.addAll(before.tags());
      if (options != null) names.addAll(options.tags().keySet());
      if (names.stream().noneMatch(name -> TagPaths.isUnder(name, from))) return List.of();
      List<TagPaths.Rename> renames = TagPaths.subtreeRenames(names, from, to);
      Map<String, String> renamed = new HashMap<>();
      for (TagPaths.Rename rename : renames) renamed.put(rename.from(), rename.to());
      List<String> ids = new ArrayList<>();
      for (NoteMeta note : store.list().stream().sorted(java.util.Comparator.comparing(NoteMeta::id)).toList()) {
        List<String> removed = note.tags().stream().filter(tag -> TagPaths.isUnder(tag, from)).toList();
        if (removed.isEmpty()) continue;
        List<String> added = removed.stream().map(renamed::get).filter(java.util.Objects::nonNull).distinct().toList();
        store.commitMeta(note.id(), new MetaWrite(null, deviceId, added.isEmpty() ? null : added, removed, null, null));
        ids.add(note.id());
      }
      store.recordTagEvents(renames, before);
      int under = renames.size() - 1;
      LOGGER.info(username + (to == null ? " removed the tag " + from : " renamed the tag " + from + " to " + to)
          + (under > 0 ? " with " + under + " tag(s) under it" : "") + " on " + ids.size() + " note(s)");
      return ids;
    }
  }

  /** A bound on a request's size: emptying a larger trash takes a few. */
  public static final int MAX_PURGES_PER_REQUEST = 1000;

  /**
   * A deleted note's text, as it was when deleted: for the trash, on a device that never had it.
   *
   * @return null if there's no such note, or it isn't deleted.
   */
  public String trashedText(String username, String noteId) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      NoteMeta meta = store.get(noteId);
      return meta == null || !meta.deleted() ? null : store.readContent(noteId);
    }
  }

  /**
   * A note's history, as editing sessions (see NoteHistory): those of a note in the trash too.
   *
   * @return newest first; null if there's no such note.
   */
  public List<NoteHistory.Entry> history(String username, String noteId) throws IOException {
    List<NoteHistory.Step> steps = noteStores.getStore(username).historySteps(noteId);
    return steps == null ? null : NoteHistory.sessions(steps);
  }

  /** @return a revision's text; null if there's no such note or revision (thinned out). */
  public String revisionText(String username, String noteId, int revision) throws IOException {
    Revision stored = noteStores.getStore(username).readRevision(noteId, revision);
    return stored == null ? null : stored.content();
  }

  /**
   * Deletes for good those of {@code noteIds} that are deleted, with their history: the others
   * (not deleted, or not yet: a device's delete not sent) are left alone. Clients forget them with
   * the next changes.
   *
   * @return the IDs of the notes deleted for good.
   */
  public List<String> purge(String username, List<String> noteIds) throws IOException {
    if (noteIds.size() > MAX_PURGES_PER_REQUEST) throw new InvalidInputException("At most " + MAX_PURGES_PER_REQUEST + " notes at a time.");
    for (String id : noteIds) {
      if (id == null || !UserNoteStore.NOTE_ID_PATTERN.matcher(id).matches()) throw new InvalidInputException("Invalid noteId.");
    }
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<String> gone = store.purge(noteIds);
      if (!gone.isEmpty()) LOGGER.info(username + " deleted " + gone.size() + " note(s) for good");
      return gone;
    }
  }

  /** Far more than anyone sets: a bound on a request's size. */
  public static final int MAX_TAG_OPTIONS_CHANGES = 1000;

  /**
   * Replaces each changed tag's options, the last change for a tag winning.
   *
   * @param knownSeq the client's cursor: tags renamed or removed since are so in the changes too (see
   *                 {@link #pushMeta}). Null: none are.
   * A change that isn't valid (an unknown color, from another build…) is skipped, not refused:
   * the rest of the push is applied already, and the client mustn't send it again forever.
   *
   * @return every tag's options now.
   * @throws InvalidInputException past {@link #MAX_TAG_OPTIONS_CHANGES}.
   */
  public Map<String, TagOptions> pushTagOptions(String username, List<TagOptionsChange> changes, Long knownSeq) throws IOException {
    if (changes.size() > MAX_TAG_OPTIONS_CHANGES) throw new InvalidInputException("At most " + MAX_TAG_OPTIONS_CHANGES + " tag options changes.");
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<TagOptionsChange> renamed = new ArrayList<>();
      for (TagOptionsChange change : changes) {
        if (change == null || !isTagName(change.tag()) || (change.options() != null && !change.options().isValid())) {
          LOGGER.warning("Tag options change of " + username + " skipped, not valid: " + change);
          continue;
        }
        String tag = change.tag();
        for (UserNoteStore.StoredTagEvent event : knownSeq == null ? List.<UserNoteStore.StoredTagEvent>of() : store.tagEventsAfter(knownSeq)) {
          if (tag != null && tag.equals(event.from())) tag = event.to();
        }
        // Set before its tag was removed everywhere: nothing to set any more.
        if (tag != null) renamed.add(new TagOptionsChange(tag, change.options()));
      }
      UserNoteStore.StoredTagOptions now = store.setTagOptions(renamed);
      return now == null ? Map.of() : now.tags();
    }
  }

  /**
   * Replaces the user's defaults for their notes: the last device to send wins.
   *
   * @return the defaults now.
   */
  public NoteDefaults pushNoteDefaults(String username, NoteDefaults defaults) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      store.setNoteDefaults(defaults);
      return store.noteDefaults();
    }
  }

  /**
   * Replaces the user's tag order, unless it changed since {@code change}'s base: the client then
   * replays its moves on the current one. Each tag must be a valid one; tags in use but missing are
   * added at the end.
   */
  public TagOrderResult pushTagOrder(String username, TagOrderChange change) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      String invalid = validate(change);
      if (invalid != null) return new TagOrderResult(TagOrderStatus.REJECTED, invalid, tagOrderOf(store.getTagOrder()));
      try {
        return new TagOrderResult(TagOrderStatus.APPLIED, null, tagOrderOf(store.setTagOrder(change.baseRevision(), change.tags())));
      } catch (UserNoteStore.StaleTagOrderException e) {
        return new TagOrderResult(TagOrderStatus.STALE, null, tagOrderOf(store.getTagOrder()));
      }
    }
  }

  private static String validate(TagOrderChange change) {
    if (change.tags() == null) return "No tags.";
    if (change.tags().size() > MAX_ORDERED_TAGS) return "At most " + MAX_ORDERED_TAGS + " tags.";
    for (String tag : change.tags()) {
      if (!isTagName(tag)) return INVALID_TAG;
    }
    return null;
  }

  private static TagOrder tagOrderOf(UserNoteStore.StoredTagOrder order) {
    return order == null ? null : new TagOrder(order.revision(), order.tags());
  }

  public ChangesPage changes(String username, long since) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<NoteMeta> changed = store.changesSince(since);
      boolean more = changed.size() > changesPageSize;
      List<NoteMeta> page = more ? changed.subList(0, changesPageSize) : changed;
      long seq = more ? page.get(page.size() - 1).seq() : store.getLastSeq();
      UserNoteStore.StoredTagOrder order = store.getTagOrder();
      // Within this page's range: a later change of the order comes with the page that reaches it.
      TagOrder tagOrder = order != null && order.seq() > since && order.seq() <= seq ? tagOrderOf(order) : null;
      List<TagEvent> events = store.tagEventsAfter(since).stream().filter(event -> event.seq() <= seq)
          .map(UserNoteStore.StoredTagEvent::event).toList();
      UserNoteStore.StoredTagOptions options = store.getTagOptions();
      Map<String, TagOptions> tagOptions = options != null && options.seq() > since && options.seq() <= seq ? options.tags() : null;
      UserNoteStore.StoredNoteDefaults defaults = store.getNoteDefaults();
      NoteDefaults noteDefaults = defaults != null && defaults.seq() > since && defaults.seq() <= seq ? defaults.defaults() : null;
      List<String> purged = store.purgesAfter(since).stream().filter(p -> p.seq() <= seq).map(UserNoteStore.StoredPurge::noteId).toList();
      return new ChangesPage(store.getStoreId(), seq, page.stream().map(NoteService::info).toList(), more, tagOrder,
          events.isEmpty() ? null : events, tagOptions, noteDefaults, purged.isEmpty() ? null : purged);
    }
  }

  /**
   * {@link #changes} as soon as there's something to report: right away if the user's seq isn't
   * {@code since} (including a cursor ahead of the server, which the client must learn about),
   * otherwise on their next change, or after {@code timeoutMillis} with nothing new. Waiting holds
   * no thread.
   */
  public CompletableFuture<ChangesPage> awaitChanges(String username, long since, long timeoutMillis) throws IOException {
    UserNoteStore store = noteStores.getStore(username);
    CompletableFuture<Void> signal = new CompletableFuture<>();
    Runnable cancel = store.awaitSeqChange(since, () -> signal.complete(null));
    if (signal.isDone()) return CompletableFuture.completedFuture(changes(username, since));

    ScheduledFuture<?> timeout = timer.schedule(() -> signal.complete(null), timeoutMillis, TimeUnit.MILLISECONDS);
    signal.whenComplete((ignored, error) -> {
      timeout.cancel(false);
      cancel.run();
    });
    // Off the thread that completed the signal: possibly a commit, holding the store's lock.
    return signal.thenApplyAsync(ignored -> {
      try {
        return changes(username, since);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  /** @throws InvalidInputException past {@link #MAX_NOTES_PER_REQUEST}. */
  public NotesResult getNotes(String username, List<NoteRequest> requests) throws IOException {
    if (requests.size() > MAX_NOTES_PER_REQUEST) {
      throw new InvalidInputException("At most " + MAX_NOTES_PER_REQUEST + " notes per request.");
    }
    UserNoteStore store = noteStores.getStore(username);
    synchronized (store) {
      List<NoteContent> notes = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      for (NoteRequest request : requests) {
        NoteMeta meta = store.get(request.id());
        if (meta == null) {
          missing.add(request.id());
        } else if (meta.deleted()) {
          notes.add(new NoteContent(info(meta), null));
        } else {
          String content = store.readContent(meta.id());
          Revision have = request.haveRevision() == null ? null : store.readRevision(meta.id(), request.haveRevision());
          notes.add(new NoteContent(info(meta), have == null ? full(content) : smallest(have.content(), content)));
        }
      }
      return new NotesResult(notes, missing);
    }
  }

  private String validate(String deviceId, NoteChange change) {
    if (change.changeId() == null || change.changeId().isEmpty() || change.changeId().length() > MAX_ID_LENGTH) return "Invalid changeId.";
    if (change.noteId() == null || !UserNoteStore.NOTE_ID_PATTERN.matcher(change.noteId()).matches()) return "Invalid noteId.";
    if (deviceId != null && deviceId.length() > MAX_ID_LENGTH) return "Invalid deviceId.";
    if (change.baseRevision() < 0) return "Invalid baseRevision.";
    if (change.deleted()) return null;
    if ((change.content() == null) == (change.splice() == null)) return "Exactly one of content and splice is expected.";
    if (change.contentHash() == null) return "contentHash is required.";
    return null;
  }

  private String validate(String deviceId, MetaChange change) {
    if (change.changeId() == null || change.changeId().isEmpty() || change.changeId().length() > MAX_ID_LENGTH) return "Invalid changeId.";
    if (change.noteId() == null || !UserNoteStore.NOTE_ID_PATTERN.matcher(change.noteId()).matches()) return "Invalid noteId.";
    if (deviceId != null && deviceId.length() > MAX_ID_LENGTH) return "Invalid deviceId.";
    for (List<String> tags : Arrays.asList(change.addTags(), change.removeTags())) {
      if (tags == null) continue;
      if (tags.size() > MAX_TAGS) return "At most " + MAX_TAGS + " tags.";
      for (String tag : tags) {
        if (!isTagName(tag)) return INVALID_TAG;
      }
    }
    // A tag added is a new name; one removed may have been made before the rules got stricter.
    if (change.addTags() != null && !change.addTags().stream().allMatch(NoteService::isValidTag)) {
      return INVALID_TAG;
    }
    if (change.setOptions() != null) {
      if (change.setOptions().size() > MAX_OPTIONS) return "At most " + MAX_OPTIONS + " options.";
      for (Map.Entry<String, String> option : change.setOptions().entrySet()) {
        if (!isValidOptionKey(option.getKey())) return "Invalid option name.";
        if (NoteOptions.PUBLISHED.equals(option.getKey())) return PUBLISHED_BY_SERVER;
        if (option.getValue() == null || option.getValue().length() > MAX_OPTION_VALUE_LENGTH) return "Invalid value for option " + option.getKey() + ".";
      }
    }
    if (change.unsetOptions() != null) {
      if (change.unsetOptions().size() > MAX_OPTIONS) return "At most " + MAX_OPTIONS + " options.";
      for (String key : change.unsetOptions()) {
        if (!isValidOptionKey(key)) return "Invalid option name.";
        if (NoteOptions.PUBLISHED.equals(key)) return PUBLISHED_BY_SERVER;
      }
    }
    return null;
  }

  /**
   * A tag name to give, once normalized (see TagPaths.normalize, which {@link #pushMeta} and
   * {@link #renameTag} apply): no empty level, 1 to {@link #MAX_TAG_LENGTH} characters per level and
   * {@link #MAX_TAG_PATH_LENGTH} in all, no whitespace, no control characters and no bidi controls,
   * which would show it as another name (U+202E reverses what follows). Joiners and variation
   * selectors are fine: emoji need them. TagTree.ts and Metadata.ts have the same rule.
   */
  static boolean isValidTag(String tag) {
    if (!isTagName(tag) || !tag.equals(TagPaths.normalize(tag))) return false;
    for (String level : tag.split(TagPaths.SEPARATOR)) if (level.codePointCount(0, level.length()) > MAX_TAG_LENGTH) return false;
    return tag.codePoints().noneMatch(c -> Character.getType(c) == Character.CONTROL || isBidiControl(c));
  }

  /**
   * A tag name to refer to, made perhaps when {@link #isValidTag} was laxer: still removable,
   * renamable, orderable.
   */
  static boolean isTagName(String tag) {
    if (tag == null || tag.isEmpty() || tag.codePointCount(0, tag.length()) > MAX_TAG_PATH_LENGTH) return false;
    return tag.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
  }

  private static boolean isBidiControl(int c) {
    return c == 0x061c || c == 0x200e || c == 0x200f || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069);
  }

  private static boolean isValidOptionKey(String key) {
    return key != null && OPTION_KEY_PATTERN.matcher(key).matches();
  }

  static boolean isValidOption(String key, String value) {
    return isValidOptionKey(key) && value != null && value.length() <= MAX_OPTION_VALUE_LENGTH;
  }

  /** The text the client's change derives from; null if it can't be found (see {@link RejectReason#BASE_GONE}). */
  private static String ancestor(UserNoteStore store, NoteChange change, NoteMeta head) throws IOException {
    if (change.basedOnChangeId() != null) {
      ChangeRecord based = store.findChange(change.basedOnChangeId());
      if (based != null && based.noteId().equals(change.noteId())) {
        Revision revision = store.readRevision(based.noteId(), based.revision());
        if (revision != null) return revision.submittedContent() != null ? revision.submittedContent() : revision.content();
      }
    }
    if (change.baseRevision() == 0) return "";
    Revision base = head == null ? null : store.readRevision(change.noteId(), change.baseRevision());
    // Unchecked, there's nothing left to check it against: a wrong one can only double some text of the user's own note.
    if (base == null) return change.baseContent() == null ? null : normalize(change.baseContent());
    return base.content();
  }

  private PushResult delete(UserNoteStore store, String deviceId, NoteChange change, NoteMeta head, String ancestor) throws IOException {
    if (head == null) return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, null, null);
    if (head.deleted()) return applied(change, head, null);
    String content = store.readContent(head.id());
    if (change.baseRevision() != head.revision()) {
      LOGGER.info("Delete of note " + head.id() + " from device " + deviceId + " refused: edited since revision " + change.baseRevision());
      return new PushResult(change.changeId(), PushStatus.KEPT, null, null, info(head), splice(ancestor, content));
    }
    NoteMeta deleted = commit(store, head, new NoteWrite(content, true, change.changeId(), deviceId, null));
    return applied(change, deleted, null);
  }

  private PushResult edit(UserNoteStore store, String username, String deviceId, NoteChange change, NoteMeta head, String ancestor)
      throws IOException {
    String submitted;
    if (change.splice() != null) {
      if (ancestor == null) return baseGone(change);
      try {
        submitted = change.splice().apply(ancestor);
      } catch (IllegalArgumentException e) {
        return rejected(change, RejectReason.BASE_MISMATCH, e.getMessage());
      }
    } else {
      submitted = change.content();
    }
    if (!Hashes.sha256(submitted).equals(change.contentHash())) {
      return rejected(change, RejectReason.BASE_MISMATCH, "The text doesn't match contentHash.");
    }
    String text = normalize(submitted);
    long size = text.getBytes(StandardCharsets.UTF_8).length;
    if (size > settings.getMaxNoteSize()) {
      return rejected(change, RejectReason.TOO_LARGE, "The note is " + size + " bytes, the limit is " + settings.getMaxNoteSize() + ".");
    }

    if (head == null) {
      if (change.baseRevision() > 0) {
        LOGGER.warning("Note " + change.noteId() + " of " + username + " unknown at revision " + change.baseRevision()
            + ", recreated from device " + deviceId);
      }
      NoteMeta created = commit(store, null, new NoteWrite(text, false, change.changeId(), deviceId, submitted), change.noteId());
      return applied(change, created, splice(submitted, text));
    }

    String current = store.readContent(head.id());
    if (ancestor == null) {
      if (text.equals(current)) {
        if (!head.deleted()) return applied(change, head, splice(submitted, text));
        NoteMeta undeleted = commit(store, head, new NoteWrite(text, false, change.changeId(), deviceId, submitted));
        return applied(change, undeleted, splice(submitted, text));
      }
      return baseGone(change);
    }
    Merge3.Result merge = Merge3.merge(ancestor, current, text);
    String merged = merge.text();
    boolean wasMerged = !ancestor.equals(current);
    if (wasMerged) {
      LOGGER.info("Note " + head.id() + " of " + username + ": merged a change from device " + deviceId
          + (merge.clashes() > 0 ? ", " + merge.clashes() + " passage(s) edited on both sides kept twice" : ""));
    }
    if (merged.equals(current) && !head.deleted()) {
      return new PushResult(change.changeId(), wasMerged ? PushStatus.MERGED : PushStatus.APPLIED, null, null, info(head),
          splice(submitted, current));
    }
    NoteMeta committed = commit(store, head, new NoteWrite(merged, false, change.changeId(), deviceId, submitted));
    return new PushResult(change.changeId(), wasMerged ? PushStatus.MERGED : PushStatus.APPLIED, null, null, info(committed),
        splice(submitted, merged));
  }

  private static PushResult baseGone(NoteChange change) {
    return rejected(change, RejectReason.BASE_GONE, "Revision " + change.baseRevision() + " is gone: send its text with the change.");
  }

  /**
   * What the change led to, as recorded: the revision it made. When that revision was thinned out,
   * or the change made a conflict copy (which older versions did), the note as it is now.
   */
  private static PushResult replay(UserNoteStore store, NoteChange change, ChangeRecord done) throws IOException {
    Revision revision = done.noteId().equals(change.noteId()) ? store.readRevision(done.noteId(), done.revision()) : null;
    if (revision == null) {
      NoteMeta now = store.get(change.noteId());
      return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, info(now), now.deleted() ? null
          : full(store.readContent(now.id())));
    }
    String submitted = revision.submittedContent() != null ? revision.submittedContent() : revision.content();
    PushStatus status = revision.submittedContent() == null ? PushStatus.APPLIED : PushStatus.MERGED;
    return new PushResult(change.changeId(), status, null, null, info(store.get(done.noteId()), revision),
        splice(submitted, revision.content()));
  }

  /** Commits the next revision of an existing note. */
  private static NoteMeta commit(UserNoteStore store, NoteMeta head, NoteWrite write) throws IOException {
    return commit(store, head, write, head.id());
  }

  /** Never stale: the caller holds the store's lock since it read {@code head}. */
  private static NoteMeta commit(UserNoteStore store, NoteMeta head, NoteWrite write, String noteId) throws IOException {
    try {
      return store.commit(noteId, head == null ? 0 : head.revision(), write);
    } catch (StaleRevisionException e) {
      throw new IllegalStateException("Note changed while its store was locked", e);
    }
  }

  private static PushResult applied(NoteChange change, NoteMeta meta, Splice content) {
    return new PushResult(change.changeId(), PushStatus.APPLIED, null, null, info(meta), content);
  }

  private static PushResult applied(String changeId, NoteMeta meta) {
    return new PushResult(changeId, PushStatus.APPLIED, null, null, info(meta), null);
  }

  private static PushResult rejected(NoteChange change, RejectReason reason, String message) {
    return rejected(change.changeId(), change.noteId(), reason, message);
  }

  private static PushResult rejected(String changeId, String noteId, RejectReason reason, String message) {
    LOGGER.warning("Change " + changeId + " to note " + noteId + " rejected (" + reason + "): " + message);
    return new PushResult(changeId, PushStatus.REJECTED, reason, message, null, null);
  }

  /** CRLF and lone CR become LF: a client on another platform mustn't turn every line into a change. */
  static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n');
  }

  /** Null when {@code from} is already {@code to}; the full text when {@code from} is unknown. */
  private static Splice splice(String from, String to) {
    if (from == null) return full(to);
    return from.equals(to) ? null : Splice.compute(from, to);
  }

  private static Splice smallest(String from, String to) {
    Splice splice = Splice.compute(from, to);
    return splice.middle().length() < to.length() ? splice : full(to);
  }

  private static Splice full(String text) {
    return new Splice(0, 0, text);
  }

  static NoteInfo info(NoteMeta meta) {
    return new NoteInfo(meta.id(), meta.revision(), meta.seq(), meta.createdAt(), meta.modifiedAt(), meta.deleted(), meta.title(),
        meta.size(), meta.contentHash(), meta.tags(), meta.options());
  }

  /** The note as it was at {@code revision}. */
  private static NoteInfo info(NoteMeta meta, Revision revision) {
    byte[] content = revision.content().getBytes(StandardCharsets.UTF_8);
    return new NoteInfo(meta.id(), revision.revision(), revision.seq(), meta.createdAt(), revision.at(), revision.deleted(),
        UserNoteStore.title(revision.content()), content.length, Hashes.sha256(content), meta.tags(), meta.options());
  }
}
