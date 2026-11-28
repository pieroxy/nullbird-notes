package net.pieroxy.notes.webserver;

import net.pieroxy.notes.utils.Hashes;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AppManifestTest {
  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private void write(File root, String path, String content) throws Exception {
    File file = new File(root, path);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
  }

  @Test
  public void listsTheAppsFilesWithTheirHashesLeavingOutThePublishedPageMapsAndTheServiceWorker() throws Exception {
    File root = tmp.newFolder("webapp-ui");
    write(root, "index.html", "<script src=\"/bundle-aaaaaa.js\"></script><link href=\"/bundle-cccccc.css\">");
    write(root, "published.html", "<script src=\"/bundle-bbbbbb.js\"></script><link href=\"/bundle-cccccc.css\">");
    write(root, "bundle-aaaaaa.js", "app");
    write(root, "bundle-aaaaaa.js.map", "{}");
    write(root, "bundle-bbbbbb.js", "published");
    write(root, "bundle-cccccc.css", "both");
    write(root, "sw.js", "worker");
    write(root, "icons/icon.svg", "<svg/>");

    AppManifest manifest = AppManifest.of("1.0+abc", root);

    assertEquals("1.0+abc", manifest.build());
    assertEquals(List.of("bundle-aaaaaa.js", "bundle-cccccc.css", "icons/icon.svg", "index.html"),
        manifest.files().stream().map(AppManifest.AppFile::path).toList());
    AppManifest.AppFile app = manifest.files().get(0);
    assertEquals(3, app.size());
    assertEquals(Hashes.sha256("app".getBytes(StandardCharsets.UTF_8)), app.sha256());
    assertEquals("application/javascript;charset=utf-8", app.type());
    assertEquals("image/svg+xml", manifest.files().get(2).type());
    assertEquals(SecurityHeadersFilter.CSP, manifest.headers().get("Content-Security-Policy"));
  }

  @Test
  public void withoutTheWebAppItsEmpty() throws Exception {
    assertEquals(List.of(), AppManifest.of("1.0+abc", new File(tmp.getRoot(), "missing")).files());
  }
}
