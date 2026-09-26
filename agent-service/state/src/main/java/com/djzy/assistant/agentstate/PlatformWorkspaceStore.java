package com.djzy.assistant.agentstate;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * 多实例共享的「工作区文件」存储（H-04）：agent 的工作区不再各自落在本机磁盘，而是落在共用的库上，
 * 于是任意实例写、任意实例读，看到的是同一份（开启方式：{@code agent-service.workspace-store=jdbc}）。
 *
 * <p><b>为什么这个类只有几行</b>：读写工作区的能力框架已经给全了——{@link JdbcStore} 实现了
 * {@link BaseStore} 契约（含 CAS 写 {@code putIfVersion}、前缀搜索 {@code search}），
 * 我们只要把它接上平台的 DataSource 就行。所以这里<strong>不是</strong>一个装饰器、不改写任何一行框架 SQL，
 * 只固定三件平台自己的事：
 *
 * <ol>
 *   <li><b>方言复用</b>：会话状态那套方言（{@link AbstractJdbcDialect}，同一份代码里 PG 与 H2 自动识别）
 *       同时就是工作区的表方言，所以生产 PG、测试 H2 走的是同一份逻辑；</li>
 *   <li><b>不在运行期建表</b>（{@code autoCreateTable(false)}）：表的唯一来源是迁移脚本
 *       {@code deploy/migrations/V17__workspace_store.sql}。让框架自己建的话，多实例同时启动会撞
 *       建表竞态，而且 DDL 就有了两处真相；</li>
 *   <li><b>装配时就确认表在</b>（见 {@link #requireTable}）：会话状态那边缺表是框架当场报错，
 *       工作区这边框架不查——不补这一下，缺表要等到「第一次真的用工作区」才炸，而且炸在某个用户的一轮对话里。
 *       改成启动就失败，坏消息来得早、也来得集中。</li>
 * </ol>
 *
 * <p><b>表名的耦合点</b>：框架的表名是固定前缀 {@code agentscope_} + {@code store} → {@code agentscope_store}
 * （由方言的 {@code storeTableName()} 决定，可以被覆盖）。平台不覆盖它，迁移脚本也照着这个名字建表；
 * 这里用方言取值而不是把名字写死，所以框架将来改了默认名，迁移脚本与代码会一起对不上——这是有意为之：
 * 宁可启动失败，也不要「表名悄悄变了、读写落到两张表上」。
 */
public final class PlatformWorkspaceStore {

    private PlatformWorkspaceStore() {}

    /**
     * 按 DataSource 建一个工作区存储（生产 PG、测试 H2 走同一份代码）。
     *
     * @throws IllegalStateException 表不存在或读不到——直接让应用起不来，见类注释第 3 条
     */
    public static BaseStore create(DataSource dataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource)
                .autoCreateTable(false)
                .build();
        requireTable(dataSource, dialect.storeTableName());
        return JdbcStore.builder(dataSource).dialect(dialect).build();
    }

    /**
     * 确认表真的在（用一条 {@code WHERE 1 = 0} 的空查询探一下，任何方言都认，也不写任何数据）。
     *
     * <p>报错信息里同时给出「去哪找建表脚本」和「底层到底报了什么」：
     * 前者处理「忘了跑迁移」，后者处理「表在但连不上 / 没权限」——两种情况都不该让运维猜。
     */
    private static void requireTable(DataSource dataSource, String table) {
        String probe = "SELECT 1 FROM " + table + " WHERE 1 = 0";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(probe)) {
            stmt.executeQuery();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "工作区共享存储不可用：读不到表 " + table
                            + "。多半是没跑建表脚本（deploy/migrations/V17__workspace_store.sql）；"
                            + "若不是，看下面的原始错误（连接或权限）。原始错误：" + e.getMessage(),
                    e);
        }
    }
}
