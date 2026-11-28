package net.pieroxy.notes.android;

import java.io.File;

/** A copy of the web app on the device: a folder with its manifest and its files, all checked. */
final class WebAppCopy {
  static final String MANIFEST = "manifest.json";
  static final String FILES = "files";

  final File dir;
  final AppManifest manifest;

  WebAppCopy(File dir, AppManifest manifest) {
    this.dir = dir;
    this.manifest = manifest;
  }

  String build() {
    return manifest.build;
  }

  /** @return the file for a path in the manifest, or null if it isn't in it. */
  AppManifest.AppFile entry(String path) {
    return manifest.files.get(path);
  }

  File file(AppManifest.AppFile entry) {
    return new File(new File(dir, FILES), entry.path);
  }
}
