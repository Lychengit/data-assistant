package com.djzy.assistant.common.skill;

import com.djzy.assistant.common.security.Hmac;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
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

 * <p><b>包根怎么认</b>：{@code manifest.json} 在压缩包最外层就是包根；如果它被裹在一层目录里
 * （「右键 → 压缩文件夹」的必然产物），就把那一层剥掉当包根——包内路径是相对包根写的，
 * 不该为了一层目录名把人挡在门外（2026-09-27 实测：多一层目录 → 上传报 500）。

 * <p><b>为什么在公共模块</b>：管理端上传时要用它做校验（§18.5.2），agent 侧下发技能到工作区时也要用它解包
 * （H-06a）。同一份解包逻辑放两处迟早会走样——尤其是这里的路径净化（zip slip）与体积上限，
 * 那是安全边界，不能有第二份实现。
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
        Map<String, byte[]> entries = new LinkedHashMap<>();
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
                entries.put(safeName(entry.getName()), zip.readAllBytes());
            }
        } catch (IOException e) {
            throw new InvalidPackageException("UNREADABLE_PACKAGE", "包不是有效的 zip：" + e.getMessage());
        }
        String root = manifestRoot(entries.keySet());
        if (root == null) {
            throw new InvalidPackageException(
                    "MANIFEST_MISSING",
                    "包内没有 " + MANIFEST_NAME + "：请把它放在压缩包的最外层——在技能文件夹里全选文件再压缩，"
                            + "不要连文件夹一起压缩");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        String manifestText = null;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = stripRoot(entry.getKey(), root);
            if (name.isEmpty()) {
                continue;
            }
            names.add(name);
            if (MANIFEST_NAME.equalsIgnoreCase(name)) {
                manifestText = new String(entry.getValue(), StandardCharsets.UTF_8);
            } else if (entry.getValue().length <= MAX_TEXT_BYTES) {
                files.put(name, entry.getValue());
            }
        }
        if (manifestText == null) {
            // 走不到：root 就是照着 manifest.json 的位置认出来的，剥掉前缀后它必定还在
            throw new InvalidPackageException("MANIFEST_MISSING", "包内没有 " + MANIFEST_NAME);
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

    /**
     * 包内容的根：返回要剥掉的前缀（内容就在最外层时是空串），一处 manifest 都没有时返回 {@code null}。
     *
     * <p>「右键 → 压缩文件夹」压出来的包必然套一层目录名（{@code my_skill/manifest.json}），
     * 而包内路径（{@code script.path} / {@code resources}）都是相对包根写的。为了少一层目录把人挡在门外
     * 不值得，这里自动认出那一层并剥掉。包内有多处 manifest 时取最浅的那个——不在多份清单之间猜。
     */
    private static String manifestRoot(Collection<String> names) {
        String root = null;
        int shallowest = Integer.MAX_VALUE;
        for (String name : names) {
            int slash = name.lastIndexOf('/');
            String base = slash < 0 ? name : name.substring(slash + 1);
            if (!MANIFEST_NAME.equalsIgnoreCase(base)) {
                continue;
            }
            int depth = (int) name.chars().filter(character -> character == '/').count();
            if (depth < shallowest) {
                shallowest = depth;
                root = slash < 0 ? "" : name.substring(0, slash + 1);
            }
        }
        return root;
    }

    /** 剥掉包根前缀；不在这层目录下的条目（如 macOS 塞进来的 {@code __MACOSX/} 垃圾）原样留着。 */
    private static String stripRoot(String name, String root) {
        return root.isEmpty() || !name.startsWith(root) ? name : name.substring(root.length());
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
