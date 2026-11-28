package net.pieroxy.notes.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class BridgeTest {
  @Test
  public void itsScriptGoesBeforeThePagesFirstScript() {
    String page = "<!doctype html><html><head><meta charset=UTF-8><title>Notes</title><script defer src=/bundle-1.js></script></head>"
        + "<body><script src=/compat.js></script></body></html>";
    assertEquals("<!doctype html><html><head><meta charset=UTF-8><title>Notes</title><script src=/nullbird-native.js></script>"
        + "<script defer src=/bundle-1.js></script></head><body><script src=/compat.js></script></body></html>", Bridge.inject(page));
  }

  @Test
  public void withoutAScriptItGoesAtTheEndOfTheHead() {
    assertEquals("<HEAD><title>x</title><script src=/nullbird-native.js></script></HEAD>", Bridge.inject("<HEAD><title>x</title></HEAD>"));
  }

  @Test
  public void itDefinesWhatPlatformTsReads() {
    assertEquals("window.NullbirdNative = Object.freeze({ platform: \"android\", serverUrl: \"https:\\/\\/notes.example.com:8443\" });\n",
        new String(Bridge.script("https://notes.example.com:8443"), StandardCharsets.UTF_8));
  }
}
