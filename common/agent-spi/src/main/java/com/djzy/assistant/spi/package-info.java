/**
 * 中立 agent 运行时端口与模型（ADR-32 / §18.10 / §18.11）。
 *
 * <p>硬约束：本模块 pom 不声明任何 agent 框架依赖，源码禁止 import 任何 agent 框架包。
 * 平台侧（agent-service/core）只依赖本模块；运行时实现（runtime-*）反向依赖本模块。
 */
package com.djzy.assistant.spi;
