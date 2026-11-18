package net.pieroxy.notes.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Writes a file so that, whatever happens, it holds either its previous content or the new one in
 * full: temp file in the same directory, fsync, atomic rename, then fsync of the directory so the
 * rename itself survives a power loss.
 */
public final class AtomicFiles {
  public static final String TMP_SUFFIX = ".tmp";

  private AtomicFiles() {}

  public static void write(Path target, byte[] content) throws IOException {
    Path dir = target.toAbsolutePath().getParent();
    Files.createDirectories(dir);
    Path tmp = dir.resolve(target.getFileName() + TMP_SUFFIX);
    try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    syncDirectory(dir);
  }

  /** Not supported everywhere (Windows can't open a directory): the rename is then as durable as the OS makes it. */
  private static void syncDirectory(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException ignored) {
      // See javadoc.
    }
  }
}
