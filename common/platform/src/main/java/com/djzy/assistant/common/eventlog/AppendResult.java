package com.djzy.assistant.common.eventlog;

/** 追加结果：记录起始位点与结束位点（结束位点用于投递确认）。 */
public record AppendResult(long startOffset, long endOffset) {
}
