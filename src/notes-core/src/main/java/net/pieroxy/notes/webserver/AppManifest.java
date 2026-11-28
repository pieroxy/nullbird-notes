package net.pieroxy.notes.webserver;

import net.pieroxy.notes.utils.Hashes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the native apps download to run the web app from the device (GET {@value AppManifestServlet#PATH}):
 * this build's files, each with its size and hash, and the headers to serve them with. The apps
 * fetch each file at its path from the server's root, check it against its hash, and serve it
 * themselves; the rest (the API, the installed fonts) they leave to the server.
 * <p>
 * Left out: source maps, the service worker (the apps keep the files themselves), and the published
 * note page (/p/, opened in the browser) with the bundles only it uses.
 *
 * @param build   the build (see BuildInfo#ID): the apps download nothing when they have it already.
 * @param files   in path order.
 * @param headers the security headers the server sends with every file (see SecurityHeadersFilter).
 */
public record AppManifest(String build, List<AppFile> files, Map<String, String> headers) {
  /**
   * @param path   relative to the server's root, with forward slashes, without a leading one.
   * @param sha256 lowercase hex.
   * @param type   the Content-Type to serve it with.
   */
  public record AppFile(String path, long size, String sha256, String type) {}

  private static final Pattern BUNDLE = Pattern.compile("bundle-[0-9a-f]+\\.(?:js|css)");
  private static final String PUBLISHED_PAGE = "published.html";
  private static final Set<String> LEFT_OUT = Set.of(PUBLISHED_PAGE, "sw.js");

  /** Reads every file of {@code webappDir}, the web app as the server serves it. */
  public static AppManifest of(String build, File webappDir) throws IOException {
    Path root = webappDir.toPath();
    Set<String> publishedOnly = publishedOnlyBundles(root);
    List<AppFile> files = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (var paths = Files.walk(root)) {
        for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
          String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
          if (relative.endsWith(".map") || LEFT_OUT.contains(relative) || publishedOnly.contains(relative)) continue;
          byte[] content = Files.readAllBytes(path);
          files.add(new AppFile(relative, content.length, Hashes.sha256(content), MimeTypes.of(relative)));
        }
      }
    }
    files.sort(Comparator.comparing(AppFile::path));
    return new AppManifest(build, List.copyOf(files), SecurityHeadersFilter.HEADERS);
  }

  /** The bundles the published page names and the app's page doesn't. */
  private static Set<String> publishedOnlyBundles(Path root) throws IOException {
    Set<String> published = bundlesNamedIn(root.resolve(PUBLISHED_PAGE));
    published.removeAll(bundlesNamedIn(root.resolve("index.html")));
    return published;
  }

  private static Set<String> bundlesNamedIn(Path page) throws IOException {
    Set<String> names = new HashSet<>();
    if (!Files.isRegularFile(page)) return names;
    Matcher matcher = BUNDLE.matcher(Files.readString(page, StandardCharsets.UTF_8));
    while (matcher.find()) names.add(matcher.group());
    return names;
  }
}
