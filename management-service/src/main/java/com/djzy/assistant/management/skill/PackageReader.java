package com.djzy.assistant.management.skill;

import com.djzy.assistant.common.security.Hmac;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包解压与 manifest 读取（§18.5.2）。
 *
 * <p>解压阶段就做**路径净化**（§18.6-7）：拒绝绝对路径与 {@code ..}，拒绝超大包与超多条目，
 * 免得 zip slip 打进存储目录。文本文件进内存供静态提示扫描；二进制只记名字。
 */
public final class PackageReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static final String MANIFEST_NAME = "manifest.json";
    public static final long MAX_PACKAGE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_ENTRIES = 200;
    private static final long MAX_TEXT_BYTES = 256L * 1024;

    private PackageReader() {}

    /** @throws InvalidPackageException 包结构不合法（不是 zip / 缺 manifest / 路径越界 / 超大） */
    public static PackageContent read(byte[] packageBytes) {
        if (packageBytes == null || packageBytes.length == 0) {
            throw new InvalidPackageException("EMPTY_PACKAGE", "空包");
        }
        if (packageBytes.length > MAX_PACKAGE_BYTES) {
            throw new InvalidPackageException("PACKAGE_TOO_LARGE", "包体超过 " + MAX_PACKAGE_BYTES + " 字节");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        String manifestText = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new InvalidPackageException("TOO_MANY_ENTRIES", "包内文件数超过 " + MAX_ENTRIES);
                }
                String name = safeName(entry.getName());
                names.add(name);
                byte[] bytes = zip.readAllBytes();
                if (MANIFEST_NAME.equalsIgnoreCase(name)) {
                    manifestText = new String(bytes, StandardCharsets.UTF_8);
                } else if (bytes.length <= MAX_TEXT_BYTES) {
                    files.put(name, bytes);
                }
            }
        } catch (IOException e) {
            throw new InvalidPackageException("UNREADABLE_PACKAGE", "包不是有效的 zip：" + e.getMessage());
        }
        if (manifestText == null) {
            throw new InvalidPackageException("MANIFEST_MISSING", "缺少 " + MANIFEST_NAME);
        }
        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(manifestText, MAP_TYPE);
        } catch (IOException e) {
            throw new InvalidPackageException("MANIFEST_UNPARSEABLE", "manifest.json 不是合法 JSON");
        }
        return new PackageContent(
                Hmac.sha256Hex(packageBytes),
                SkillManifest.from(raw),
                manifestText,
                Hmac.sha256Hex(manifestText.getBytes(StandardCharsets.UTF_8)),
                Map.copyOf(files),
                List.copyOf(names));
    }

    /** zip slip 防护：不允许绝对路径、盘符、{@code ..} 段。 */
    private static String safeName(String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("..")) {
            throw new InvalidPackageException("UNSAFE_ENTRY_PATH", "包内路径不安全：" + name);
        }
        return normalized;
    }

    /** 包结构不合法（**一律拒绝上传**，不给「先存下来再说」的机会）。 */
    public static final class InvalidPackageException extends RuntimeException {
        private final String code;

        public InvalidPackageException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
