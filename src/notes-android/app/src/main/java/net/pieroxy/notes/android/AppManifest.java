package net.pieroxy.notes.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The server's GET /app/manifest.json (see AppManifest on the server): the web app's build, its
 * files, and the headers to serve them with. Everything in it is checked, since its paths name files
 * written on the device.
 */
final class AppManifest {
  static final String INDEX = "index.html";
  private static final int MAX_FILES = 1000;
  private static final long MAX_FILE_SIZE = 64L * 1024 * 1024;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._+-]*");
  private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");

  static final class AppFile {
    final String path;
    final long size;
    final String sha256;
    final String type;

    AppFile(String path, long size, String sha256, String type) {
      this.path = path;
      this.size = size;
      this.sha256 = sha256;
      this.type = type;
    }

    /** The type without its parameters: text/html for text/html;charset=utf-8. */
    String mimeType() {
      int semicolon = type.indexOf(';');
      return (semicolon < 0 ? type : type.substring(0, semicolon)).trim();
    }

    /** The charset parameter, or null. */
    String charset() {
      for (String part : type.split(";")) {
        String param = part.trim();
        if (param.regionMatches(true, 0, "charset=", 0, 8)) return param.substring(8).trim();
      }
      return null;
    }
  }

  final String build;
  /** By path, in the manifest's order. */
  final Map<String, AppFile> files;
  final Map<String, String> headers;
  /** As the server sent it: kept with the copy. */
  final String json;

  private AppManifest(String build, Map<String, AppFile> files, Map<String, String> headers, String json) {
    this.json = json;
    this.build = build;
    this.files = Collections.unmodifiableMap(files);
    this.headers = Collections.unmodifiableMap(headers);
  }

  /** @throws InvalidManifestException if it isn't a valid manifest. */
  static AppManifest parse(String json) throws InvalidManifestException {
    try {
      JSONObject root = new JSONObject(json);
      String build = root.getString("build");
      if (build.isEmpty() || hasControl(build)) throw new InvalidManifestException("Invalid build.");

      JSONArray list = root.getJSONArray("files");
      if (list.length() > MAX_FILES) throw new InvalidManifestException("Too many files.");
      Map<String, AppFile> files = new LinkedHashMap<>();
      for (int i = 0; i < list.length(); i++) {
        JSONObject entry = list.getJSONObject(i);
        String path = entry.getString("path");
        long size = entry.getLong("size");
        String sha256 = entry.getString("sha256");
        String type = entry.getString("type");
        if (!isSafePath(path)) throw new InvalidManifestException("Invalid path: " + path);
        if (size < 0 || size > MAX_FILE_SIZE) throw new InvalidManifestException("Invalid size: " + path);
        if (!SHA256.matcher(sha256).matches()) throw new InvalidManifestException("Invalid hash: " + path);
        if (type.isEmpty() || hasControl(type)) throw new InvalidManifestException("Invalid type: " + path);
        if (files.put(path, new AppFile(path, size, sha256, type)) != null) throw new InvalidManifestException("Listed twice: " + path);
      }
      if (!files.containsKey(INDEX)) throw new InvalidManifestException("No " + INDEX + ".");

      Map<String, String> headers = new LinkedHashMap<>();
      JSONObject given = root.getJSONObject("headers");
      for (Iterator<String> names = given.keys(); names.hasNext(); ) {
        String name = names.next();
        String value = given.getString(name);
        if (!HEADER_NAME.matcher(name).matches() || hasControl(value)) throw new InvalidManifestException("Invalid header: " + name);
        headers.put(name, value);
      }
      return new AppManifest(build, files, headers, json);
    } catch (JSONException e) {
      throw new InvalidManifestException(e.getMessage());
    }
  }

  /**
   * A relative path, with forward slashes, made of plain names: nothing that could reach out of the
   * folder it's written to.
   */
  static boolean isSafePath(String path) {
    if (path.isEmpty() || path.length() > 255) return false;
    for (String segment : path.split("/", -1)) {
      if (!SEGMENT.matcher(segment).matches() || segment.equals("..")) return false;
    }
    return true;
  }

  private static boolean hasControl(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 || c == 0x7f) return true;
    }
    return false;
  }

  List<AppFile> fileList() {
    return new ArrayList<>(files.values());
  }

  static final class InvalidManifestException extends Exception {
    InvalidManifestException(String message) {
      super(message);
    }
  }
}
