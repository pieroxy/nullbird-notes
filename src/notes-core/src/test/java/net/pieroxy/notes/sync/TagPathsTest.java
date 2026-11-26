package net.pieroxy.notes.sync;

import org.junit.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagPathsTest {
  private static final Comparator<String> ALPHABETICAL = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

  @Test
  public void emptyLevelsGo() {
    assertEquals("work/clients", TagPaths.normalize("/work//clients/"));
    assertEquals("work", TagPaths.normalize("work"));
    assertEquals("", TagPaths.normalize("//"));
  }

  @Test
  public void aTagIsUnderItselfAndItsAncestorsOnly() {
    assertTrue(TagPaths.isUnder("work/clients", "work"));
    assertTrue(TagPaths.isUnder("work", "work"));
    assertFalse(TagPaths.isUnder("workshop", "work"));
    assertFalse(TagPaths.isUnder("work", "work/clients"));
    assertEquals(List.of("work", "work/clients"), TagPaths.ancestors("work/clients/acme"));
    assertEquals(List.of(), TagPaths.ancestors("work"));
    assertEquals("work/clients", TagPaths.parent("work/clients/acme"));
    assertEquals(null, TagPaths.parent("work"));
  }

  @Test
  public void tagsAboveAnotherOneAreRedundant() {
    assertEquals(List.of("work/clients", "home"), TagPaths.withoutRedundant(List.of("work", "work/clients", "home")));
  }

  @Test
  public void treeOrderPutsEachTagBeforeThoseUnderItSiblingsInTheUsersOrder() {
    List<String> names = List.of("home", "work/meetings", "work/clients/acme", "urgent");
    // A tag's place is that of the first of it and those under it.
    List<String> order = List.of("urgent", "work/clients/acme", "home", "work/meetings");
    assertEquals(List.of("urgent", "work", "work/clients", "work/clients/acme", "work/meetings", "home"),
        TagPaths.treeOrder(names, order, ALPHABETICAL));
    assertEquals(List.of("home", "urgent", "work", "work/clients", "work/clients/acme", "work/meetings"),
        TagPaths.treeOrder(names, null, ALPHABETICAL));
  }

  @Test
  public void aRenameTakesTheTagsUnderItTheTopOneFirst() {
    assertEquals(List.of(new TagPaths.Rename("work", "job"), new TagPaths.Rename("work/clients", "job/clients"),
            new TagPaths.Rename("work/clients/acme", "job/clients/acme")),
        TagPaths.subtreeRenames(List.of("work/clients/acme", "workshop", "work/clients", "home"), "work", "job"));
    assertEquals(List.of(new TagPaths.Rename("work", null), new TagPaths.Rename("work/x", null)),
        TagPaths.subtreeRenames(List.of("work/x"), "work", null));
  }
}
