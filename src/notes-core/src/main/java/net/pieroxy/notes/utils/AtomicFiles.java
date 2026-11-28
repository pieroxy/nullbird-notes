package net.pieroxy.notes.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Writes a file so that, whatever happens, it holds either its previous content or the new one in
 * full: temp file in the same directory, fsync, atomic rename, then fsync of the directory so the
 * rename itself survives a power loss. A file replaced keeps its permissions: the temp file gets
 * them before any content is written, so a file readable by its owner only never is by others.
 */
public final class AtomicFiles {
  public static final String TMP_SUFFIX = ".tmp";

  private AtomicFiles() {}

  public static void write(Path target, byte[] content) throws IOException {
    Path dir = target.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = dir.resolve(target.getFileName() + TMP_SUFFIX);
    Set<PosixFilePermission> permissions = posixPermissions(target);
    try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
      if (permissions != null) Files.setPosixFilePermissions(tmp, permissions);
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    syncDirectory(dir);
  }

  /** @return null if {@code file} doesn't exist yet, or the file system has no POSIX permissions. */
  private static Set<PosixFilePermission> posixPermissions(Path file) throws IOException {
    try {
      return Files.getPosixFilePermissions(file);
    } catch (NoSuchFileException | UnsupportedOperationException e) {
      return null;
    }
  }

  /** Not supported everywhere (Windows can't open a directory): the rename is then as durable as the OS makes it. */
  public static void syncDirectory(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException ignored) {
      // See javadoc.
    }
  }
}
