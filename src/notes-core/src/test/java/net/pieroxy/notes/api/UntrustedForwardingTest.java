package net.pieroxy.notes.api;

import net.pieroxy.notes.webserver.AccessValve;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UntrustedForwardingTest {
  private static final Logger VALVE_LOGGER = Logger.getLogger(AccessValve.class.getName());

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private TestServer server;
  private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
  private final Handler capture = new Handler() {
    @Override
    public void publish(LogRecord record) {
      if (record.getLevel() == Level.WARNING && record.getMessage().contains("X-Forwarded-For")) warnings.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  };

  @Before
  public void setUp() throws Exception {
    VALVE_LOGGER.addHandler(capture);
    server = new TestServer(tmp.getRoot(), Map.of("alice", "alice-password"), Set.of());
  }

  @After
  public void tearDown() {
    server.close();
    VALVE_LOGGER.removeHandler(capture);
  }

  /** Tests run in parallel: other servers' warnings are told apart by the forwarded addresses, used by this test only. */
  @Test
  public void forwardingWithoutTrustedProxiesIsWarnedAboutOnce() throws Exception {
    TestServer.Client client = server.client();
    client.raw("/");
    client.rawForwardedFor("/", "198.18.7.1");
    client.rawForwardedFor("/", "198.18.7.2");

    List<String> mine = warnings.stream().map(LogRecord::getMessage).filter(m -> m.contains("198.18.7.")).toList();
    assertEquals(mine.toString(), 1, mine.size());
    assertTrue(mine.get(0), mine.get(0).startsWith("A request from 127.0.0.1 carries X-Forwarded-For (198.18.7.1), but trustedProxies is empty."));
  }
}
