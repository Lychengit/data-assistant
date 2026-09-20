package com.djzy.assistant.tck;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.runtime.noop.NoopRuntimeAdapter;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 契约回归：假实现（runtime-noop）必须全绿，作为其它实现的对照基线（§16-9）。 */
class NoopRuntimeTckTest {

    @Test
    void noopRuntimePassesTck() {
        TckReport report = RuntimeTck.run(new NoopRuntimeAdapter());
        assertTrue(
                report.allPassed(),
                () -> "TCK 未通过：\n" + report.failures().stream().collect(Collectors.joining("\n")));
    }
}
