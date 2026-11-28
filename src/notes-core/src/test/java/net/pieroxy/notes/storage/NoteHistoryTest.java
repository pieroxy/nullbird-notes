package net.pieroxy.notes.storage;

import net.pieroxy.notes.storage.NoteHistory.Entry;
import net.pieroxy.notes.storage.NoteHistory.HistoryKind;
import net.pieroxy.notes.storage.NoteHistory.Step;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class NoteHistoryTest {
  private static final long T0 = 1_763_481_600_000L;
  private static final long SECOND = 1000;
  private static final long MINUTE = 60 * SECOND;

  /** Steps one after the other, built from what each changed. */
  private static class Steps {
    final List<Step> steps = new ArrayList<>();
    long at = T0;

    Steps save(long after, String device, int removed, int added) {
      return add(after, device, false, removed, added);
    }

    Steps add(long after, String device, boolean deleted, int removed, int added) {
      at += after;
      steps.add(new Step(steps.size() + 1, at, device, deleted, removed, added));
      return this;
    }

    /** Typing: {@code count} saves of a few characters, 5 seconds apart. */
    Steps typing(int count, String device) {
      for (int i = 0; i < count; i++) save(5 * SECOND, device, 0, 12);
      return this;
    }

    List<Entry> sessions() {
      return NoteHistory.sessions(steps);
    }
  }

  /** Each session as "first-last" revisions, oldest first. */
  private static List<String> ranges(List<Entry> entries) {
    List<String> ranges = new ArrayList<>();
    for (int i = entries.size() - 1; i >= 0; i--) {
      Entry entry = entries.get(i);
      ranges.add((entry.revision() - entry.saves() + 1) + "-" + entry.revision());
    }
    return ranges;
  }

  @Test
  public void savesLessThanTenMinutesApartAreOneSessionNewestFirst() {
    List<Entry> entries = new Steps().save(0, "laptop", 0, 0).typing(5, "laptop").save(9 * MINUTE, "laptop", 0, 3)
        .save(11 * MINUTE, "laptop", 0, 3).typing(2, "laptop").sessions();

    assertEquals(List.of("1-7", "8-10"), ranges(entries));
    Entry last = entries.get(0);
    assertEquals(10, last.revision());
    assertEquals(3, last.saves());
    assertEquals(last.to() - 10 * SECOND, last.from());
    assertEquals(HistoryKind.EDITED, last.kind());
    assertEquals("the first one created the note", HistoryKind.CREATED, entries.get(1).kind());
  }

  @Test
  public void anotherDeviceStartsAnotherSession() {
    assertEquals(List.of("1-3", "4-5", "6-6"),
        ranges(new Steps().save(0, "laptop", 0, 0).typing(2, "laptop").typing(2, "phone").typing(1, "laptop").sessions()));
  }

  @Test
  public void aBigRemovalEndsTheSessionBeforeIt() {
    // Five minutes of typing, everything removed at once, five more minutes.
    Steps steps = new Steps().save(0, "laptop", 0, 0).typing(5, "laptop").save(5 * SECOND, "laptop", 60, 0).typing(3, "laptop");
    assertEquals(List.of("1-6", "7-10"), ranges(steps.sessions()));
  }

  @Test
  public void removalsOverSeveralSavesAddUp() {
    // Backspace a word at a time: 6 characters per save, 4 saves in a row.
    Steps steps = new Steps().save(0, "laptop", 0, 0).typing(3, "laptop");
    for (int i = 0; i < 4; i++) steps.save(2 * SECOND, "laptop", 6, 0);
    steps.typing(2, "laptop");
    assertEquals(List.of("1-4", "5-10"), ranges(steps.sessions()));
  }

  @Test
  public void smallFixesWhileTypingDontCut() {
    // Typos fixed as they come: a few characters removed, more added, save after save.
    Steps steps = new Steps().save(0, "laptop", 0, 0);
    for (int i = 0; i < 30; i++) steps.save(3 * SECOND, "laptop", 3, 15);
    // 20 characters removed in all: not more than 20.
    steps.save(3 * SECOND, "laptop", 10, 0).save(3 * SECOND, "laptop", 10, 0);
    assertEquals(List.of("1-33"), ranges(steps.sessions()));
  }

  @Test
  public void aBigReplacementCutsThoughItAddsMore() {
    // Everything selected, then something longer pasted over it.
    Steps steps = new Steps().save(0, "laptop", 0, 0).typing(2, "laptop").save(3 * SECOND, "laptop", 200, 300).typing(1, "laptop");
    assertEquals(List.of("1-3", "4-5"), ranges(steps.sessions()));
  }

  @Test
  public void theTrashAndBackAreEntriesOfTheirOwn() {
    List<Entry> entries = new Steps().save(0, "laptop", 0, 0).typing(2, "laptop")
        .add(5 * SECOND, "laptop", true, 0, 0)
        .add(5 * SECOND, "laptop", false, 0, 0).typing(2, "laptop").sessions();

    assertEquals(List.of("1-3", "4-4", "5-5", "6-7"), ranges(entries));
    assertEquals(List.of(HistoryKind.EDITED, HistoryKind.RESTORED, HistoryKind.DELETED, HistoryKind.CREATED),
        entries.stream().map(Entry::kind).toList());
  }

  @Test
  public void aNoteWhoseFirstRevisionsWereThinnedOutWasntCreatedInWhatsLeft() {
    List<Step> steps = List.of(new Step(4, T0, "laptop", false, 0, 0), new Step(9, T0 + 3 * MINUTE, "laptop", false, 0, 5));
    List<Entry> entries = NoteHistory.sessions(steps);
    assertEquals(List.of(new Entry(9, T0, T0 + 3 * MINUTE, 2, HistoryKind.EDITED)), entries);
    assertEquals(List.of(), NoteHistory.sessions(List.of()));
  }

  @Test
  public void whatASaveChangedCountsCharactersRemovedAndAdded() {
    assertArrayEquals(new int[]{0, 6}, NoteHistory.changed("Hello", "Hello world"));
    assertArrayEquals(new int[]{6, 0}, NoteHistory.changed("Hello world", "Hello"));
    // Two places at once: only what changed in each, not all between them.
    assertArrayEquals(new int[]{2, 2}, NoteHistory.changed("one two three four five", "One two three four fivE"));
    assertArrayEquals(new int[]{0, 0}, NoteHistory.changed("same", "same"));
  }
}
