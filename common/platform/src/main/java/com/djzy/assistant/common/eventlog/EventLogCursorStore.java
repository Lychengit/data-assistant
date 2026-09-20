package com.djzy.assistant.common.eventlog;

/** 投递确认位点存储（§19.6）：记录「已确认投递到队列」的字节位点，供重启后追赶重投。 */
public interface EventLogCursorStore {

    long load();

    void save(long ackedOffset);
}
