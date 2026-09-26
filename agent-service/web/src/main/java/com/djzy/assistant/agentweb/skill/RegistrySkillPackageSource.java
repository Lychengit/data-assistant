package com.djzy.assistant.agentweb.skill;

import com.djzy.assistant.common.skill.PackageContent;
import com.djzy.assistant.common.skill.PackageReader;
import com.djzy.assistant.common.storage.ObjectStorage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能包来源的实现：**认版本读管理端的表，取字节读对象存储**（H-06a / DR-41）。
 *
 * <p>为什么 agent 侧直接读 {@code sys_skill_version} 而不是让管理端开一个接口：
 * <ul>
 *   <li>「哪个版本算数」的权威只有一处——管理端发布时写的那张表。开接口等于给这份判断加一层中转，
 *       还得再造一套服务间鉴权；而两个服务本来就共用同一个库、同一个对象存储；</li>
 *   <li>多副本下每个实例都能自给自足：不依赖管理端此刻活着，也不存在「管理端挂了大家都没技能」；</li>
 *   <li>这张表在这里是**只读**的——agent 侧永远不写它，写者仍然只有管理端一个。</li>
 * </ul>
 *
 * <p>「算数」的口径与管理端一致：**该技能下最新的一条 {@code published}**（按主键倒序）。
 * 为什么按主键而不是按版本号字符串：版本号是人写的（{@code 1.0.0} / {@code v2} 混着来），
 * 字符串比较会把 {@code 10.0.0} 排到 {@code 9.0.0} 前面。
 *
 * <p>为什么「最新一条 published」而不是「最新一条」（不管状态）：管理端的口径是「发布只对最新一封生效」，
 * 所以最新一封可能还是个草稿（上传了但没发布）。那种情况下**线上跑的是上一次发布的版本**，
 * 这里就取它，不能因为「最新那封没发布」而把技能整个撤下来。
 */
public final class RegistrySkillPackageSource implements SkillPackageSource {

    private static final Logger log = LoggerFactory.getLogger(RegistrySkillPackageSource.class);

    private final DataSource dataSource;
    private final ObjectStorage storage;

    public RegistrySkillPackageSource(DataSource dataSource, ObjectStorage storage) {
        this.dataSource = dataSource;
        this.storage = storage;
    }

    @Override
    public Map<String, Published> published(List<String> skillCodes) {
        if (skillCodes == null || skillCodes.isEmpty()) {
            return Map.of();
        }
        List<String> wanted = new ArrayList<>(new java.util.LinkedHashSet<>(skillCodes));
        String placeholders = String.join(",", Collections.nCopies(wanted.size(), "?"));
        // 每个技能取「最新一条 published」：先按 skill_code 分组取主键最大的那一条。
        // 用 JOIN 而不是 DISTINCT ON：这条 SQL 要同时在 PG（生产）与 H2（用例）上跑。
        String sql = "SELECT s.skill_code, s.version, s.content_sha256, s.storage_key"
                + " FROM sys_skill_version s"
                + " JOIN (SELECT skill_code, MAX(id) AS id FROM sys_skill_version"
                + "         WHERE status = 'published' AND skill_code IN (" + placeholders + ")"
                + "         GROUP BY skill_code) latest ON latest.id = s.id";
        Map<String, Published> found = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < wanted.size(); i++) {
                ps.setString(i + 1, wanted.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("skill_code");
                    found.put(code, new Published(
                            code,
                            rs.getString("version"),
                            rs.getString("content_sha256"),
                            rs.getString("storage_key")));
                }
            }
        } catch (SQLException e) {
            // 查不到 = 算不出「该下发哪一版」。不发下去比发错版本安全，但也不能装作成功：
            // 抛出去让调用方统一处理（下发失败不影响这一轮对话，见 SkillProvisioner 的约定）。
            throw new IllegalStateException("查询已发布技能版本失败：" + e.getMessage(), e);
        }
        return Map.copyOf(found);
    }

    @Override
    public Optional<PackageContent> load(Published published) {
        Optional<byte[]> bytes = storage.get(published.storageKey());
        if (bytes.isEmpty()) {
            log.warn("技能包的存储键在对象存储里找不到：skill={} version={} key={}",
                    published.skillCode(), published.version(), published.storageKey());
            return Optional.empty();
        }
        try {
            return Optional.of(PackageReader.read(bytes.get()));
        } catch (PackageReader.InvalidPackageException e) {
            // 入库时已经过了同一套校验，这里再失败说明存储里那份坏了 / 被换了。
            // 记下来、这一次跳过；不要让它把整轮对话带崩。
            log.error("技能包解不开（存储里的那份可能已被损坏或替换）：skill={} version={} 原因={}",
                    published.skillCode(), published.version(), e.getMessage());
            return Optional.empty();
        }
    }
}
