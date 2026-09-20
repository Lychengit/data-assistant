package com.djzy.assistant.tck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TCK 报告：逐用例结论 + 汇总。 */
public final class TckReport {

    private final String runtimeId;
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, String> skipped = new LinkedHashMap<>();

    public TckReport(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public void pass(String name) {
        results.put(name, null);
    }

    public void fail(String name, String reason) {
        results.put(name, reason);
    }

    public void skip(String name, String reason) {
        skipped.put(name, reason);
    }

    public boolean allPassed() {
        return results.values().stream().allMatch(java.util.Objects::isNull);
    }

    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        results.forEach((name, reason) -> {
            if (reason != null) {
                failures.add(name + ": " + reason);
            }
        });
        return failures;
    }

    public Map<String, String> results() {
        return results;
    }

    public Map<String, String> skipped() {
        return skipped;
    }

    public String runtimeId() {
        return runtimeId;
    }
}
