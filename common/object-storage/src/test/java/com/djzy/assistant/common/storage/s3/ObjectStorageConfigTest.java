package com.djzy.assistant.common.storage.s3;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.storage.LocalFileObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**「换对象存储只加一个分支」这件事的守门测试：开关的分发逻辑在这里被钉住。 */
class ObjectStorageConfigTest {

    private final ObjectStorageConfig config = new ObjectStorageConfig();

    @Test
    void 默认与local都落到文件系统实现(@TempDir Path root) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setRootDir(root.toString());

        assertInstanceOf(LocalFileObjectStorage.class, config.objectStorage(properties));

        properties.setProvider("local");
        assertInstanceOf(LocalFileObjectStorage.class, config.objectStorage(properties));
    }

    @Test
    void 写错的provider直接报错而不是悄悄降级(@TempDir Path root) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setRootDir(root.toString());
        properties.setProvider("oss");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> config.objectStorage(properties));

        assertTrue(failure.getMessage().contains("oss"));
    }

    @Test
    void 文件系统实现的根目录会被建出来(@TempDir Path root) throws Exception {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        Path nested = root.resolve("a/b");
        properties.setRootDir(nested.toString());

        config.objectStorage(properties);

        assertTrue(Files.isDirectory(nested));
    }
}