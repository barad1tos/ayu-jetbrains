package dev.ayuislands.studio;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Separate-process lock observation with no IDE or Kotlin runtime dependency. */
public final class StudioLockProbe {
    private StudioLockProbe() {}

    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            System.out.println(lock == null ? "BUSY" : "ACQUIRED");
        }
    }
}
