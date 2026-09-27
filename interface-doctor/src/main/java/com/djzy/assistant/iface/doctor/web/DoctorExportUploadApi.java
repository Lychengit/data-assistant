package com.djzy.assistant.iface.doctor.web;

import com.djzy.assistant.common.api.ApiEnvelope;
import com.djzy.assistant.common.api.ApiResult;
import com.djzy.assistant.common.security.Hmac;
import com.djzy.assistant.common.storage.ObjectStorage;
import com.djzy.assistant.iface.doctor.repo.ArtifactRegistry;
import com.djzy.assistant.iface.doctor.service.DoctorQueryResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口：把导出文件写进对象存储并返回短期下载链接（{@code POST /doctor/export/upload}）。
 *
 * <p>这就是规格里「写操作额外三步」（§18.4.5 W1–W3）的落点：
 * <ul>
 *   <li><b>W1 防重复 + 验确认</b>：确认凭据由网关一次性消费（这里只做存在性兜底，见
 *       {@code RegisteredApiAspect}）；内容用 SHA-256 寻址，同一份 Excel 重复上传不会产生第二个对象；</li>
 *   <li><b>W2 执行</b>：写对象存储；文件名做净化（防路径攻击）；返回**短期有效**的下载链接；</li>
 *   <li><b>W3 登记</b>：往 {@code artifact} 写一条「谁在什么时候导出了什么」，供事后追溯。</li>
 * </ul>
 *
 * <p>为什么入参是 base64 而不是 multipart：本服务的每个接口都必须是「入参 {@code ApiEnvelope<T>}、
 * 返回 {@link ApiResult}」——身份在请求体里（签名只覆盖 body，§20.1.1），而 multipart 的表单体
 * 没法走这套信封与验签。base64 的代价是体积涨 1/3，换来的是这条链路与其他接口**完全同一条路径**
 * （验签、判定、审计、确认门一个都不少）。
 *
 * <p>为什么扩展名有白名单：把一个 {@code .html} / {@code .svg} 写进对象存储、再给它一个可直接下载的
 * 链接，等于在本平台域名下托管了一个可执行脚本（存储型 XSS）。白名单只留「打开就是文件」的类型，
 * 宁可拒掉一个冷门格式，也不留一个可脚本化的落点。
 *
 * <p>为什么脚本类导出不走这里直连：脚本在沙箱里没有凭据（§18.4.4），
 * 它是把文件写到挂载目录、由宿主代理代调本接口——沙箱永远拿不到令牌与密钥。
 */
@RestController
public class DoctorExportUploadApi {

    public static final String PATH = "/doctor/export/upload";

    /** 单文件上限：够放一份几万行的 Excel，又不会让一次请求把内存与网关拖垮。 */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    /** base64 长度上限（先按长度拦，避免把 20MB 的 decode 做完才发现超限）。 */
    private static final int MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4 + 8;

    /** 下载链接有效期：导出件是敏感数据，链接越短命越好（§18.4.5 W2「短期有效」）。 */
    private static final Duration LINK_TTL = Duration.ofMinutes(30);

    /** 对象保留期（§19.10 默认 7 天），与链接有效期是两件事：链接到点失效，对象到点清理。 */
    private static final Duration OBJECT_RETENTION = Duration.ofDays(7);

    /** 可下载的类型白名单（按扩展名）。只留「打开就是文件」，挡住 .html / .svg 这类可脚本化的落点。 */
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("xlsx", "xls", "csv", "pdf", "docx", "doc", "txt", "json", "png", "jpg", "jpeg", "zip");

    /** 文件名里只留「字母 / 数字 / 点 / 下划线 / 中划线 / 中文」——路径分隔符、控制字符一律换掉。 */
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]");

    private static final int MAX_NAME_LENGTH = 120;

    private final ObjectStorage objectStorage;
    private final ArtifactRegistry artifactRegistry;

    public DoctorExportUploadApi(ObjectStorage objectStorage, ArtifactRegistry artifactRegistry) {
        this.objectStorage = objectStorage;
        this.artifactRegistry = artifactRegistry;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult upload(@Valid @RequestBody ApiEnvelope<Args> body) {
        Args args = body.args();
        String fileName = sanitize(args.fileName());
        requireAllowedExtension(fileName);

        byte[] content = decode(args.contentBase64());
        String sha256 = Hmac.sha256Hex(content);

        // 内容寻址（§19.2 同一套口径）：内容相同就是同一个对象，重复导出不会把存储写胖，
        // 也让「执行记录里那个哈希」永远能找回同一份文件。
        String storageKey = "exports/" + sha256 + "/" + fileName;
        objectStorage.putIfAbsent(storageKey, content);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(OBJECT_RETENTION);
        String artifactId = UUID.randomUUID().toString();
        artifactRegistry.register(
                artifactId, blankToNull(args.sessionId()), fileName, content.length,
                "object-storage://" + storageKey, expiresAt);

        // 链接拿不到不是错误：本机文件系统实现没有签名 URL 这回事。这时如实回 null，
        // 而不是回一个指向本机路径的假链接——那会让调用方以为"下载地址就是它"。
        String downloadUrl = objectStorage.presignedGetUrl(storageKey, LINK_TTL).orElse(null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("artifact_id", artifactId);
        row.put("file_name", fileName);
        row.put("size_bytes", content.length);
        row.put("content_sha256", sha256);
        row.put("storage_key", storageKey);
        row.put("download_url", downloadUrl);
        row.put("download_url_expires_at", downloadUrl == null ? null : now.plus(LINK_TTL).toString());
        row.put("object_expires_at", expiresAt.toString());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("service", "interface-doctor");
        provenance.put("httpPath", PATH);
        provenance.put("requestId", body.caller().requestId());
        provenance.put("artifactId", artifactId);
        provenance.put("linkTtlSeconds", LINK_TTL.toSeconds());

        // 走同一个 ApiResult 契约：审计切面因此自动记下"这次写操作成功了、产出了一条记录"。
        return DoctorQueryResult.of(PATH, List.of(row), 1, provenance);
    }

    /** 文件名净化：去掉任何路径成分，只留放心字符，并限长（§18.6-7「文件名净化」）。 */
    private static String sanitize(String rawName) {
        String base = rawName == null ? "" : rawName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = UNSAFE_NAME_CHARS.matcher(base).replaceAll("_");
        // 开头是点（. / .. / .bashrc 这类）一律打掉：它既可能是隐藏文件，也可能是路径技巧的残留。
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(cleaned.length() - MAX_NAME_LENGTH);
        }
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("file_name 净化后为空");
        }
        return cleaned;
    }

    private static void requireAllowedExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不允许上传的文件类型：" + extension);
        }
    }

    private static byte[] decode(String contentBase64) {
        if (contentBase64.length() > MAX_BASE64_CHARS) {
            throw new IllegalArgumentException("文件超过上限 " + MAX_BYTES + " 字节");
        }
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("content_base64 不是合法 base64");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过上限 " + MAX_BYTES + " 字节");
        }
        return content;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 入参：字段名必须与 {@code sys_api.param_schema.properties} 一致（启动自检会对账）。
     *
     * <p>{@code sandbox_path} 是**平台与模型之间**的约定，不参与这里的业务：模型在对话里只给一个
     * 沙箱内的路径，宿主代理（agent-service）把那份文件的字节读出来、填进 {@code content_base64}，
     * 再把 {@code sandbox_path} **摘掉**之后才发到本服务。所以这个字段在本服务里永远读不到值，
     * 它存在只为了让"注册表与 DTO 字段一致"这条自检继续成立。
     *
     * <p>为什么不干脆让模型搬运 base64：实测它会打印截断预览、再凭碎片拼出一份打不开的坏文件
     * （2026-09-27）。不透明的长字符串不该经过语言模型，路径才该。
     */
    public record Args(
            @NotBlank(message = "file_name 必填") @JsonProperty("file_name") String fileName,
            @NotBlank(message = "content_base64 必填") @JsonProperty("content_base64") String contentBase64,
            @JsonProperty("sandbox_path") String sandboxPath,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("artifact_name") String artifactName) {}
}