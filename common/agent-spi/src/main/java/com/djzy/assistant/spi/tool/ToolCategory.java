package com.djzy.assistant.spi.tool;

/** 工具类别（§4.8 节点 A 的工具前缀来源）。 */
public enum ToolCategory {
    /** 直连接口工具，名前缀 {@code iface_}。 */
    IFACE,
    /** 技能工具，名前缀 {@code skill_}。 */
    SKILL,
    /** 沙箱脚本工具，名前缀 {@code py_}。 */
    SANDBOX_SCRIPT,
    /** 平台内置工具。 */
    PLATFORM
}
