package com.djzy.assistant.common.eventlog;

/**
 * 会话日志卷不可用（§19.6 / §13）：**卷不可用则拒绝启动**——缺卷宁可不可用，
 * 不得静默退化到内存或临时目录。
 */
public class EventLogUnavailableException extends RuntimeException {

    public EventLogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
