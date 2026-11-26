package net.pieroxy.notes.services;

import net.pieroxy.notes.sync.TagOrderChange;

import net.pieroxy.notes.sync.TagOptionsChange;

import net.pieroxy.notes.sync.TagOptions;

import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.storage.NoteOptions;
import net.pieroxy.notes.sync.MetaChange;
import net.pieroxy.notes.sync.NoteChange;
import net.pieroxy.notes.sync.PushResult;
import net.pieroxy.notes.sync.PushStatus;
import net.pieroxy.notes.utils.Hashes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NotePublishingTest {
  private static final String A = "00000001-0000-4000-8000-000000000001";

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private NoteService service;
  private NoteStoreService stores;
  private int changeCount;

  @Before
  public void setUp() {
    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(1);
    SettingsService settings = new SettingsService(config, new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    stores = new NoteStoreService();
    service = new NoteService();
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), stores, service,
        new ImportService(), new FontService(), new WebServerService());
    stores.init(provider);
    service.init(provider);
  }

  @After
  public void tearDown() {
    service.destroy();
  }

  private PushResult create(String user, String noteId, String content) throws IOException {
    return service.push(user, "device", new NoteChange("change-" + (++changeCount), noteId, 0, null, content, null, Hashes.sha256(content), false, null));
  }

  /** A fresh service on the same data: its index is built from what's stored. */
  private NoteService restarted() {
    Configuration config = new Configuration();
    SettingsService settings = new SettingsService(config, new File(tmp.getRoot(), "credentials.json"), tmp.getRoot());
    NoteStoreService freshStores = new NoteStoreService();
    NoteService fresh = new NoteService();
    ServiceProvider provider = new ServiceProvider(settings, new SessionService(), new ClientGuardService(), freshStores, fresh,
        new ImportService(), new FontService(), new WebServerService());
    freshStores.init(provider);
    fresh.init(provider);
    return fresh;
  }

  @Test
  public void aPublishedNoteIsFoundByItsToken() throws IOException {
    create("alice", A, "Title\nbody");
    String token = service.publish("alice", A, false);
    assertTrue(NoteService.TOKEN_PATTERN.matcher(token).matches());
    NoteService.PublishedNote found = service.findPublished(token);
    assertNotNull(found);
    assertEquals("alice", found.username());
    assertEquals("Title\nbody", found.content());
    assertEquals(token, found.meta().options().get(NoteOptions.PUBLISHED));
  }

  @Test
  public void publishingAgainKeepsTheLinkUnlessANewOneIsAsked() throws IOException {
    create("alice", A, "x");
    String first = service.publish("alice", A, false);
    assertEquals(first, service.publish("alice", A, false));
    String second = service.publish("alice", A, true);
    assertNotEquals(first, second);
    assertNull(service.findPublished(first));
    assertNotNull(service.findPublished(second));
  }

  @Test
  public void anUnpublishedOrDeletedNoteIsntFound() throws IOException {
    create("alice", A, "x");
    String token = service.publish("alice", A, false);
    assertTrue(service.unpublish("alice", A));
    assertNull(service.findPublished(token));

    String again = service.publish("alice", A, false);
    service.push("alice", "device", new NoteChange("del", A, 1, null, null, null, null, true, null));
    assertNull(service.findPublished(again));
    assertNull(service.publish("alice", A, false));
  }

  @Test
  public void theTextIsLive() throws IOException {
    create("alice", A, "one");
    String token = service.publish("alice", A, false);
    service.push("alice", "device", new NoteChange("edit", A, 1, null, "two", null, Hashes.sha256("two"), false, null));
    assertEquals("two", service.findPublished(token).content());
  }

  @Test
  public void unknownOrMalformedTokensFindNothing() throws IOException {
    assertNull(service.findPublished(null));
    assertNull(service.findPublished("short"));
    assertNull(service.findPublished("AAAAAAAAAAAAAAAAAAAAAA"));
  }

  @Test
  public void theIndexIsRebuiltFromTheStoredNotes() throws IOException {
    create("alice", A, "a");
    create("bob", A, "b");
    String alices = service.publish("alice", A, false);
    String bobs = service.publish("bob", A, false);
    NoteService fresh = restarted();
    try {
      assertEquals("a", fresh.findPublished(alices).content());
      assertEquals("b", fresh.findPublished(bobs).content());
      // Built: later changes keep it up to date.
      fresh.unpublish("alice", A);
      assertNull(fresh.findPublished(alices));
    } finally {
      fresh.destroy();
    }
  }

  @Test
  public void syncCantSetOrUnsetTheOption() throws IOException {
    create("alice", A, "x");
    service.publish("alice", A, false);
    PushResult set = service.pushMeta("alice", "device", new MetaChange("m1", A, null, null, Map.of(NoteOptions.PUBLISHED, "AAAAAAAAAAAAAAAAAAAAAA"), null));
    assertEquals(PushStatus.REJECTED, set.status());
    PushResult unset = service.pushMeta("alice", "device", new MetaChange("m2", A, null, null, null, List.of(NoteOptions.PUBLISHED)));
    assertEquals(PushStatus.REJECTED, unset.status());
  }

  private static TagOptions noteOptions(Boolean markdown, Boolean monospace, Boolean wrap) {
    return new TagOptions(null, null, null, null, null, null, markdown, monospace, wrap, null, null, null, null);
  }

  @Test
  public void thePublishedNoteFollowsItsTagsUnlessItSaysOtherwise() throws Exception {
    create("alice", A, "# Title");
    service.pushMeta("alice", "device", new MetaChange("m1", A, java.util.List.of("journal", "code"), null, null, null));
    service.pushTagOptions("alice", java.util.List.of(new TagOptionsChange("journal", noteOptions(true, null, false)),
        new TagOptionsChange("code", noteOptions(false, true, true))), null);
    String token = service.publish("alice", A, false);
    NoteService.PublishedNote found = service.findPublished(token);
    // Alphabetical without an order: code first.
    assertFalse(found.markdown());
    assertTrue(found.monospace());
    assertFalse(found.nowrap());

    service.pushTagOrder("alice", new TagOrderChange(0, java.util.List.of("journal", "code")));
    found = service.findPublished(token);
    assertTrue(found.markdown());
    assertTrue(found.nowrap());

    // Its own option wins.
    service.pushMeta("alice", "device", new MetaChange("m2", A, null, null, java.util.Map.of("markdown", "false", "nowrap", "false"), null));
    found = service.findPublished(token);
    assertFalse(found.markdown());
    assertFalse(found.nowrap());
  }

  @Test
  public void aNestedTagFollowsTheTagsAboveItUnlessItSetsItsOwn() throws Exception {
    create("alice", A, "x");
    service.pushMeta("alice", "device", new MetaChange("m1", A, List.of("code/python"), null, null, null));
    service.pushTagOptions("alice", List.of(new TagOptionsChange("code", noteOptions(true, true, false))), null);
    String token = service.publish("alice", A, false);
    NoteService.PublishedNote found = service.findPublished(token);
    assertTrue(found.markdown());
    assertTrue(found.monospace());
    assertTrue(found.nowrap());

    service.pushTagOptions("alice", List.of(new TagOptionsChange("code/python", noteOptions(null, false, null))), null);
    found = service.findPublished(token);
    assertTrue(found.markdown());
    assertFalse(found.monospace());

    // "code" next to it says nothing more: "code/python" is more specific, whatever the order.
    service.pushMeta("alice", "device", new MetaChange("m2", A, List.of("code"), null, null, null));
    service.pushTagOrder("alice", new TagOrderChange(0, List.of("code", "code/python")));
    assertFalse(service.findPublished(token).monospace());
  }
}
