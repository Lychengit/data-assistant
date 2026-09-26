package com.djzy.assistant.common.skill;

import java.util.List;
import java.util.Map;

/**
 * 解压后的技能包内容（只读）。
 *
 * @param sha256 整包内容的 SHA-256（内容寻址的命名依据，§19.2）
 * @param manifest manifest 解析后的结构化视图
 * @param manifestJson manifest 的**原始文本**（审核对象就是这份文本，改一个字节哈希就变）
 * @param manifestSha256 manifest 文本的 SHA-256
 * @param files 包内文件（相对路径 → 字节）；只保留文本与脚本，扫描危险能力用

 * <p><b>为什么在公共模块</b>：管理端上传时要用它做校验（§18.5.2），agent 侧下发技能到工作区时也要用它解包
 * （H-06a）。同一份解包逻辑放两处迟早会走样——尤其是这里的路径净化（zip slip）与体积上限，
 * 那是安全边界，不能有第二份实现。
 */
public record PackageContent(
        String sha256,
        SkillManifest manifest,
        String manifestJson,
        String manifestSha256,
        Map<String, byte[]> files,
        List<String> entryNames) {

    public boolean has(String path) {
        return path != null && files.containsKey(path);
    }

    public String text(String path) {
        byte[] bytes = files.get(path);
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
