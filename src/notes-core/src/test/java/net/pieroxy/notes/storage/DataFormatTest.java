package net.pieroxy.notes.storage;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DataFormatTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private Path data;
  private final List<Integer> ran = new ArrayList<>();

  @Before
  public void setUp() {
    data = tmp.getRoot().toPath();
  }

  private Path file() {
    return data.resolve(DataFormat.FILE_NAME);
  }

  private DataFormat.Migration migration(int from) {
    return new DataFormat.Migration() {
      @Override
      public int from() {
        return from;
      }

      @Override
      public void migrate(Path dataFolder) {
        ran.add(from);
      }
    };
  }

  @Test
  public void aNewDataFolderGetsTheCurrentVersion() throws Exception {
    Files.createDirectories(data.resolve("logs"));
    DataFormat.upgrade(data, 3, List.of(migration(1), migration(2)));

    assertEquals("{\"version\":3}", Files.readString(file()));
    assertEquals(List.of(), ran);
  }

  @Test
  public void dataWithoutFormatJsonIsVersionOneAndGetsMigrated() throws Exception {
    Files.createDirectories(data.resolve("users"));
    DataFormat.upgrade(data, 3, List.of(migration(2), migration(1)));

    assertEquals(List.of(1, 2), ran);
    assertEquals("{\"version\":3}", Files.readString(file()));
  }

  @Test
  public void sessionsAloneAreData() throws Exception {
    Files.writeString(data.resolve("sessions.json"), "{}");
    DataFormat.upgrade(data, 2, List.of(migration(1)));

    assertEquals(List.of(1), ran);
  }

  @Test
  public void theCurrentVersionIsLeftAlone() throws Exception {
    Files.writeString(file(), "{\"version\": 2}");
    DataFormat.upgrade(data, 2, List.of(migration(1)));

    assertEquals(List.of(), ran);
  }

  @Test
  public void aNewerVersionRefusesToStart() throws Exception {
    Files.writeString(file(), "{\"version\": 3}");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> DataFormat.upgrade(data, 2, List.of()));

    assertTrue(e.getMessage(), e.getMessage().contains("format 3"));
    assertEquals("{\"version\": 3}", Files.readString(file()));
  }

  @Test
  public void anUnreadableFormatRefusesToStart() throws Exception {
    for (String content : List.of("", "{", "{}", "{\"version\": 0}", "{\"version\": \"x\"}")) {
      Files.writeString(file(), content);
      assertThrows(content, IllegalStateException.class, () -> DataFormat.upgrade(data, 1, List.of()));
    }
  }

  @Test
  public void anInterruptedMigrationResumesWhereItStopped() throws Exception {
    Files.writeString(file(), "{\"version\": 1}");
    DataFormat.Migration failing = new DataFormat.Migration() {
      @Override
      public int from() {
        return 2;
      }

      @Override
      public void migrate(Path dataFolder) throws IOException {
        throw new IOException("disk full");
      }
    };
    assertThrows(IOException.class, () -> DataFormat.upgrade(data, 3, List.of(migration(1), failing)));
    assertEquals("{\"version\":2}", Files.readString(file()));

    DataFormat.upgrade(data, 3, List.of(migration(1), migration(2)));
    assertEquals(List.of(1, 2), ran);
  }

  @Test
  public void aMissingMigrationRefusesToStart() throws Exception {
    Files.writeString(file(), "{\"version\": 1}");

    assertThrows(IllegalStateException.class, () -> DataFormat.upgrade(data, 2, List.of()));
  }

  @Test
  public void theRealUpgradeOfANewFolderWritesTheCurrentVersion() throws Exception {
    DataFormat.upgrade(data);

    assertEquals("{\"version\":" + DataFormat.CURRENT_VERSION + "}", Files.readString(file()));
  }
}
