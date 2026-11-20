package net.pieroxy.notes.storage;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ThinningPolicyTest {
  private static final long NOW = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli();
  private static final long HOUR = Duration.ofHours(1).toMillis();
  private static final long DAY = Duration.ofDays(1).toMillis();

  private static long ago(Duration duration) {
    return NOW - duration.toMillis();
  }

  private static ThinningPolicy.Entry entry(int revision, long at) {
    return new ThinningPolicy.Entry(revision, at, false);
  }

  private static Set<Integer> keep(List<ThinningPolicy.Entry> entries) {
    return new TreeSet<>(ThinningPolicy.keep(entries, NOW));
  }

  @Test
  public void keepsEverythingOfTheLastSevenDays() {
    List<ThinningPolicy.Entry> entries = new ArrayList<>();
    for (int i = 1; i <= 10; i++) entries.add(entry(i, ago(Duration.ofDays(6)) + i));

    assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), keep(entries));
  }

  @Test
  public void keepsTheLastOfEachHourUpToThreeMonths() {
    long hour = ago(Duration.ofDays(30)) / HOUR * HOUR;
    List<ThinningPolicy.Entry> entries = List.of(entry(1, hour - 10), entry(2, hour + 1), entry(3, hour + 2), entry(4, hour + HOUR - 1),
        entry(5, hour + HOUR), entry(6, hour + HOUR + 1), entry(7, ago(Duration.ofDays(1))));

    assertEquals(Set.of(1, 4, 6, 7), keep(entries));
  }

  @Test
  public void keepsTheLastOfEachDayPastThreeMonths() {
    long day = ago(Duration.ofDays(200)) / DAY * DAY;
    List<ThinningPolicy.Entry> entries = List.of(entry(1, day + 1), entry(2, day + 1000), entry(3, day + DAY - 1), entry(4, day + DAY + 5),
        entry(5, day + DAY + 6), entry(6, ago(Duration.ofDays(100))));

    assertEquals(Set.of(1, 3, 5, 6), keep(entries));
  }

  @Test
  public void alwaysKeepsTheFirstTheHeadAndDeletesAndUndeletes() {
    long day = ago(Duration.ofDays(200)) / DAY * DAY;
    List<ThinningPolicy.Entry> entries = List.of(entry(1, day + 1), entry(2, day + 2), new ThinningPolicy.Entry(3, day + 3, true),
        entry(4, day + 4), entry(5, day + 5), entry(6, day + 6));

    assertEquals(Set.of(1, 3, 4, 6), keep(entries));
  }

  @Test
  public void gapsLeftByAnEarlierThinningDontMatter() {
    long day = ago(Duration.ofDays(200)) / DAY * DAY;
    List<ThinningPolicy.Entry> entries = List.of(entry(1, day + 1), entry(5, day + 5), entry(9, day + 9), entry(12, ago(Duration.ofHours(1))));

    assertEquals(Set.of(1, 9, 12), keep(entries));
  }

  @Test
  public void theSubmittedTextGoesAfterADay() {
    assertFalse(ThinningPolicy.dropsSubmitted(ago(Duration.ofHours(23)), NOW));
    assertTrue(ThinningPolicy.dropsSubmitted(ago(Duration.ofDays(1)), NOW));
  }
}
