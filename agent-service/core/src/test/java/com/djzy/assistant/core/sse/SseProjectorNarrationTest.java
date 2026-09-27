package com.djzy.assistant.core.sse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 非中文旁白的识别口径（§5.1 输出语言要求）。
 *
 * <p>实测（2026-09-27）：系统提示词里写着「全程用中文」，模型仍然会给出
 * {@code "I'll load the export skill first."} 这类英文旁白，中文结论照旧。改不了模型，
 * 至少要**看得出来**还剩多少违规，所以这里守一个保守的判定：整段一个汉字都没有、
 * 又有足够多拉丁字母，才算非中文旁白。
 */
class SseProjectorNarrationTest {

    @Test
    void 纯英文旁白要被认出来() {
        assertTrue(SseProjector.isNonChineseNarration("I'll load the export skill first."));
        assertTrue(SseProjector.isNonChineseNarration("Now I'll build the input artifact and run the script."));
    }

    @Test
    void 中文旁白不算() {
        assertFalse(SseProjector.isNonChineseNarration("文件已生成（6 行），现在发起上传。"));
    }

    @Test
    void 中文里夹英文词不算() {
        assertFalse(SseProjector.isNonChineseNarration("调用 iface_doctor_export_upload 上传，参数用 sandbox_path。"));
    }

    @Test
    void 短英文片段不算_工具名路径代码本来就该是英文() {
        assertFalse(SseProjector.isNonChineseNarration("perf.xlsx"));
        assertFalse(SseProjector.isNonChineseNarration("/workspace/out/perf.xlsx"));
        assertFalse(SseProjector.isNonChineseNarration("{\"ok\": true}"));
        assertFalse(SseProjector.isNonChineseNarration(""));
        assertFalse(SseProjector.isNonChineseNarration(null));
    }
}
