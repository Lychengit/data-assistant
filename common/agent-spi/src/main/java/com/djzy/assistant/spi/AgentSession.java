package com.djzy.assistant.spi;

/** 运行时会话句柄：只暴露中立元数据，框架句柄由实现自身持有。 */
public interface AgentSession {

    String sessionId();

    String runtimeId();

    String runtimeVersion();
}
