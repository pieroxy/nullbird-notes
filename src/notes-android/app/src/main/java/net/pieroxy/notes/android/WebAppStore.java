package net.pieroxy.notes.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;

/**
 * The web app on the device, in one folder:
 * <ul>
 *   <li>{@code state}: the server and the copy in use, written last, in one go;</li>
 *   <li>{@code copy-<hash of the build>/}: a copy, its manifest and its files, each checked against
 *       its hash before the folder gets its name;</li>
 *   <li>{@code tmp-…/}: a download under way, or left over: deleted when the store opens.</li>
 * </ul>
 * A half-done download is never used: a copy's folder appears whole, and the state names it after.
 */
final class WebAppStore {
  private static final String STATE = "state";
  private static final String COPY_PREFIX = "copy-";
  private static final String TMP_PREFIX = "tmp-";
  private static final int MAX_MANIFEST_BYTES = 1024 * 1024;

  /** What the app runs: the server's address, and its web app on the device. */
  static final class Installed {
    final String server;
    final WebAppCopy copy;

    Installed(String server, WebAppCopy copy) {
      this.server = server;
      this.copy = copy;
    }
  }

  private final File root;
  private final Fetcher fetcher;

  WebAppStore(File root, Fetcher fetcher) {
    this.root = root;
    this.fetcher = fetcher;
    File[] children = root.listFiles();
    if (children != null) {
      for (File child : children) if (child.getName().startsWith(TMP_PREFIX)) deleteTree(child);
    }
  }

  /** @return what was installed last, or null: nothing yet, or it can't be read. */
  synchronized Installed installed() {
    File state = new File(root, STATE);
    if (!state.isFile()) return null;
    Properties props = new Properties();
    try (InputStream in = new FileInputStream(state)) {
      props.load(in);
      String server = props.getProperty("server");
      String dir = props.getProperty("copy");
      if (server == null || dir == null || !dir.startsWith(COPY_PREFIX) || dir.contains("/")) return null;
      return new Installed(server, load(new File(root, dir)));
    } catch (IOException | AppManifest.InvalidManifestException e) {
      return null;
    }
  }

  /**
   * @throws AppManifest.InvalidManifestException if the server answered something else: it isn't a
   *     Notes server.
   */
  AppManifest fetchManifest(String server) throws IOException, AppManifest.InvalidManifestException {
    byte[] body;
    try (InputStream in = fetcher.open(server + "/app/manifest.json")) {
      body = readAtMost(in, MAX_MANIFEST_BYTES);
    }
    return AppManifest.parse(new String(body, StandardCharsets.UTF_8));
  }

  /**
   * Downloads every file of {@code manifest} and checks it; the copy isn't used until
   * {@link #use}. If that build is on the device already, it's reused.
   *
   * @throws CorruptDownloadException if a file isn't what the manifest says.
   */
  WebAppCopy download(String server, AppManifest manifest) throws IOException {
    File target = new File(root, COPY_PREFIX + Hashes.sha256(manifest.build).substring(0, 32));
    if (target.isDirectory()) {
      try {
        WebAppCopy existing = load(target);
        if (existing.build().equals(manifest.build)) return existing;
      } catch (IOException | AppManifest.InvalidManifestException ignored) {
        // Unreadable: downloaded again below.
      }
    }

    File tmp = new File(root, TMP_PREFIX + UUID.randomUUID());
    try {
      File files = new File(tmp, WebAppCopy.FILES);
      for (AppManifest.AppFile entry : manifest.fileList()) {
        File out = new File(files, entry.path);
        File parent = out.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create " + parent);
        fetchChecked(server + "/" + entry.path, entry, out);
      }
      writeText(new File(tmp, WebAppCopy.MANIFEST), manifest.json);
      synchronized (this) {
        if (target.exists()) deleteTree(target);
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
      }
      return new WebAppCopy(target, manifest);
    } finally {
      if (tmp.exists()) deleteTree(tmp);
    }
  }

  /** Makes {@code copy} the one in use for {@code server}, and deletes the other copies. */
  synchronized void use(String server, WebAppCopy copy) throws IOException {
    Properties props = new Properties();
    props.setProperty("server", server);
    props.setProperty("copy", copy.dir.getName());
    File tmp = new File(root, STATE + ".tmp");
    try (OutputStream out = new FileOutputStream(tmp)) {
      props.store(out, null);
    }
    Files.move(tmp.toPath(), new File(root, STATE).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    File[] children = root.listFiles();
    if (children != null) {
      for (File child : children) {
        if (child.getName().startsWith(COPY_PREFIX) && !child.equals(copy.dir)) deleteTree(child);
      }
    }
  }

  /** Deletes everything: the state and every copy. */
  synchronized void clear() {
    File[] children = root.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
  }

  private WebAppCopy load(File dir) throws IOException, AppManifest.InvalidManifestException {
    byte[] json = Files.readAllBytes(new File(dir, WebAppCopy.MANIFEST).toPath());
    return new WebAppCopy(dir, AppManifest.parse(new String(json, StandardCharsets.UTF_8)));
  }

  private void fetchChecked(String url, AppManifest.AppFile entry, File out) throws IOException {
    MessageDigest digest = Hashes.sha256();
    long size = 0;
    try (InputStream in = fetcher.open(url); OutputStream os = new FileOutputStream(out)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        size += read;
        if (size > entry.size) throw new CorruptDownloadException(entry.path + " is larger than the manifest says.");
        digest.update(buffer, 0, read);
        os.write(buffer, 0, read);
      }
    }
    if (size != entry.size) throw new CorruptDownloadException(entry.path + " is smaller than the manifest says.");
    if (!Hashes.hex(digest.digest()).equals(entry.sha256)) throw new CorruptDownloadException(entry.path + " doesn't match its hash.");
  }

  private static byte[] readAtMost(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    int read;
    while ((read = in.read(buffer)) != -1) {
      if (out.size() + read > max) throw new IOException("Too large.");
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static void writeText(File file, String text) throws IOException {
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  static void deleteTree(File file) {
    if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteTree(child);
    }
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }

  static final class CorruptDownloadException extends IOException {
    CorruptDownloadException(String message) {
      super(message);
    }
  }
}
