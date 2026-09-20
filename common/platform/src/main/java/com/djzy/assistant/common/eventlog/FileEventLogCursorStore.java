package com.djzy.assistant.common.eventlog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件位点存储：与日志同目录、同卷，随日志一起持久化；写入后 fsync 保证不丢。
 *
 * <p>骨架期为单实例卷；多副本用各自 instanceId 的日志文件，互不影响（§2.3 无状态设计）。
 */
public final class FileEventLogCursorStore implements EventLogCursorStore {

    private final Path path;

    public FileEventLogCursorStore(Path path) {
        this.path = path;
    }

    @Override
    public long load() {
        try {
            if (!Files.exists(path)) {
                return 0L;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? 0L : Long.parseLong(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void save(long ackedOffset) {
        try {
            Files.writeString(path, Long.toString(ackedOffset), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
