package net.pieroxy.notes.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A fake server: a build, its files, and its manifest; and the URLs asked for. */
class TestServer implements Fetcher {
  static final String ADDRESS = "https://notes.example.com";

  final Map<String, byte[]> files = new LinkedHashMap<>();
  /** Served in place of the real content, to test the checks. */
  final Map<String, byte[]> tampered = new HashMap<>();
  final List<String> fetched = new ArrayList<>();
  String build;
  boolean down;

  TestServer(String build) {
    setBuild(build);
  }

  void setBuild(String build) {
    this.build = build;
    files.clear();
    files.put("index.html", ("<html>" + build + "</html>").getBytes(StandardCharsets.UTF_8));
    files.put("bundle-" + Hashes.sha256(build).substring(0, 8) + ".js", ("// " + build).getBytes(StandardCharsets.UTF_8));
    files.put("icons/icon-192.png", new byte[]{1, 2, 3});
  }

  String manifest() {
    try {
      return manifestJson();
    } catch (org.json.JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  private String manifestJson() throws org.json.JSONException {
    JSONArray list = new JSONArray();
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      list.put(new JSONObject()
          .put("path", file.getKey())
          .put("size", file.getValue().length)
          .put("sha256", Hashes.hex(Hashes.sha256().digest(file.getValue())))
          .put("type", file.getKey().endsWith(".html") ? "text/html;charset=utf-8" : "application/octet-stream"));
    }
    return new JSONObject()
        .put("build", build)
        .put("files", list)
        .put("headers", new JSONObject().put("Content-Security-Policy", "default-src 'none'"))
        .toString();
  }

  @Override
  public InputStream open(String url) throws IOException {
    if (down) throw new java.net.ConnectException("down");
    fetched.add(url);
    if (!url.startsWith(ADDRESS + "/")) throw new NotFoundException(url);
    String path = url.substring(ADDRESS.length() + 1);
    if (path.equals("app/manifest.json")) return new ByteArrayInputStream(manifest().getBytes(StandardCharsets.UTF_8));
    byte[] content = tampered.containsKey(path) ? tampered.get(path) : files.get(path);
    if (content == null) throw new NotFoundException(url);
    return new ByteArrayInputStream(content);
  }
}
