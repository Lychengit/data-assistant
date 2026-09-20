package com.djzy.assistant.management.repo;

import java.time.Instant;

/** 技能包版本行（{@code sys_skill_version}）的读取视图。 */
public record SkillVersionView(
        long id,
        String skillCode,
        String version,
        String contentSha256,
        String storageKey,
        String manifestJson,
        String manifestSha256,
        String kind,
        String status,
        String submittedBy,
        String publishedBy,
        Instant publishedAt,
        Instant createdAt) {

    public boolean published() {
        return "published".equals(status);
    }
}
