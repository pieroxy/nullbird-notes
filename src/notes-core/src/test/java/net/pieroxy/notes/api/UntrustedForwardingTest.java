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

  @Test
  public void forwardingWithoutTrustedProxiesIsWarnedAboutOnce() throws Exception {
    TestServer.Client client = server.client();
    client.raw("/");
    assertEquals("no header, no warning", 0, warnings.size());

    client.rawForwardedFor("/", "203.0.113.7");
    client.rawForwardedFor("/", "203.0.113.8");

    assertEquals(1, warnings.size());
    assertTrue(warnings.get(0).getMessage(), warnings.get(0).getMessage().contains("A request from 127.0.0.1 carries X-Forwarded-For"));
  }
}
