package net.pieroxy.notes.config;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigurationTest {
  @Test
  public void theSessionCookieIsSecureUnlessTurnedOff() {
    assertTrue(new Configuration().isSecureCookie());
    assertTrue(new Gson().fromJson("{}", Configuration.class).isSecureCookie());
    assertTrue(new Gson().fromJson("{\"secureCookie\": true}", Configuration.class).isSecureCookie());
    assertFalse(new Gson().fromJson("{\"secureCookie\": false}", Configuration.class).isSecureCookie());
  }
}
