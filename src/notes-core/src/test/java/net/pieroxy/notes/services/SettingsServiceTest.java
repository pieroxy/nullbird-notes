package net.pieroxy.notes.services;

import com.google.gson.Gson;
import net.pieroxy.notes.config.Configuration;
import net.pieroxy.notes.config.CredentialsFile;
import net.pieroxy.notes.config.PasswordHasher;
import net.pieroxy.notes.config.UserCredential;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SettingsServiceTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private File credentialsFile() {
    return new File(tmp.getRoot(), "credentials.json");
  }

  private SettingsService start() {
    SettingsService settings = new SettingsService(new Configuration(), credentialsFile(), tmp.getRoot());
    settings.start();
    return settings;
  }

  private void writeUsers(String... usernames) throws IOException {
    List<UserCredential> users = new ArrayList<>();
    for (String username : usernames) {
      UserCredential user = new UserCredential();
      user.setUsername(username);
      user.setPassword(PasswordHasher.hash("secret-" + username));
      users.add(user);
    }
    CredentialsFile file = new CredentialsFile();
    file.setUsers(users);
    write(new Gson().toJson(file));
  }

  /** Bumps the modification time too: two writes within the file system's timestamp resolution would look unchanged. */
  private void write(String content) throws IOException {
    File file = credentialsFile();
    long previous = file.lastModified();
    Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    file.setLastModified(Math.max(previous + 2000, file.lastModified()));
  }

  @Test
  public void derivesTheRequestCapFromTheNoteSize() {
    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(3);
    SettingsService settings = new SettingsService(config, credentialsFile(), tmp.getRoot());

    assertEquals(3 * 1024 * 1024, settings.getMaxNoteSize());
    assertEquals(6 * 1024 * 1024 + 64 * 1024, settings.getMaxRequestBytes());
  }

  @Test
  public void refusesANoteSizeBelowOneMegabyte() {
    Configuration config = new Configuration();
    config.setMaxNoteSizeMb(0);
    assertThrows(IllegalArgumentException.class, () -> new SettingsService(config, credentialsFile(), tmp.getRoot()));
  }

  @Test
  public void loadsUsersAndVerifiesTheirPasswords() throws IOException {
    writeUsers("alice", "bob");
    SettingsService settings = start();

    assertTrue(PasswordHasher.verify("secret-alice", settings.getUser("alice").getPassword()));
    assertNotNull(settings.getUser("bob"));
    assertNull(settings.getUser("carol"));
    assertNull(settings.getUser(null));
  }

  @Test
  public void picksUpUsersAddedWithoutARestart() throws IOException {
    writeUsers("alice");
    SettingsService settings = start();
    writeUsers("alice", "carol");

    assertNotNull(settings.getUser("carol"));
  }

  @Test
  public void keepsThePreviousUsersWhenAChangeIsInvalid() throws IOException {
    writeUsers("alice");
    SettingsService settings = start();
    write("{ not json");

    assertNotNull(settings.getUser("alice"));
  }

  @Test
  public void refusesToStartOnAnInvalidUsername() throws IOException {
    writeUsers("../alice");
    IllegalStateException e = assertThrows(IllegalStateException.class, this::start);
    assertTrue(e.getMessage(), e.getMessage().contains("invalid username"));
  }

  @Test
  public void refusesToStartOnADuplicateUser() throws IOException {
    writeUsers("alice", "alice");
    IllegalStateException e = assertThrows(IllegalStateException.class, this::start);
    assertTrue(e.getMessage(), e.getMessage().contains("declared twice"));
  }

  @Test
  public void refusesToStartWithoutAPasswordHash() throws IOException {
    write("{\"users\":[{\"username\":\"alice\"}]}");
    IllegalStateException e = assertThrows(IllegalStateException.class, this::start);
    assertEquals("Could not load " + credentialsFile() + ": user \"alice\" has no password hash and salt", e.getMessage());
  }

  @Test
  public void readsTheAdminFlagWhichIsOffUnlessSaid() throws IOException {
    String hash = new Gson().toJson(PasswordHasher.hash("secret"));
    write("{\"users\":[{\"username\":\"alice\",\"admin\":true,\"password\":" + hash + "},"
        + "{\"username\":\"bob\",\"password\":" + hash + "}]}");

    SettingsService settings = start();

    assertTrue(settings.getUser("alice").isAdmin());
    assertFalse(settings.getUser("bob").isAdmin());
  }

  @Test
  public void aPasswordChangeKeepsTheAdminFlag() throws IOException {
    write("{\"users\":[{\"username\":\"alice\",\"admin\":true,\"password\":" + new Gson().toJson(PasswordHasher.hash("secret")) + "}]}");
    SettingsService settings = start();

    settings.setPassword("alice", PasswordHasher.hash("another secret"));

    assertTrue(settings.getUser("alice").isAdmin());
    assertTrue(PasswordHasher.verify("another secret", settings.getUser("alice").getPassword()));
  }

  @Test
  public void addsUsersAndSwitchesTheirAdminFlagKeepingWhatItDoesntKnow() throws IOException {
    write("{\"comment\":\"kept\",\"users\":[{\"username\":\"alice\",\"admin\":true,\"future\":{\"x\":1},\"password\":"
        + new Gson().toJson(PasswordHasher.hash("secret")) + "}]}");
    SettingsService settings = start();

    settings.addUser("bob", "bob@example.com", false, PasswordHasher.hash("bob secret"), Instant.parse("2026-11-21T06:30:00Z"));
    settings.addUser("carol", null, true, PasswordHasher.hash("carol secret"), Instant.parse("2026-11-21T06:31:00Z"));
    settings.setAdmin("bob", true);
    settings.setAdmin("carol", false);

    assertEquals(List.of("alice", "bob", "carol"), settings.listUsers().stream().map(UserCredential::getUsername).toList());
    UserCredential bob = settings.getUser("bob");
    assertTrue(bob.isAdmin());
    assertEquals("bob@example.com", bob.getEmail());
    assertEquals(Instant.parse("2026-11-21T06:30:00Z"), bob.createdAtInstant());
    assertTrue(PasswordHasher.verify("bob secret", bob.getPassword()));
    assertFalse(settings.getUser("carol").isAdmin());
    assertNull(settings.getUser("carol").getEmail());
    String json = Files.readString(credentialsFile().toPath(), StandardCharsets.UTF_8);
    assertTrue(json, json.contains("\"comment\": \"kept\"") && json.contains("\"future\": {"));
    assertFalse("cleared, not false", json.contains("\"admin\": false"));
    assertThrows(IllegalArgumentException.class, () -> settings.addUser("bob", null, false, PasswordHasher.hash("x"), Instant.now()));
    assertThrows(IllegalArgumentException.class, () -> settings.addUser("../x", null, false, PasswordHasher.hash("x"), Instant.now()));
    assertThrows(IllegalArgumentException.class, () -> settings.setAdmin("nobody", true));
  }

  @Test
  public void refusesToStartOnAnInvalidCreationDate() throws IOException {
    write("{\"users\":[{\"username\":\"alice\",\"createdAt\":\"yesterday\",\"password\":" + new Gson().toJson(PasswordHasher.hash("secret")) + "}]}");
    IllegalStateException e = assertThrows(IllegalStateException.class, this::start);
    assertTrue(e.getMessage(), e.getMessage().contains("invalid createdAt"));
  }
}
