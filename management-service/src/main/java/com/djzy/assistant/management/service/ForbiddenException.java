package com.djzy.assistant.management.service;

import com.djzy.assistant.common.error.UnifiedErrors;

/** 已认证但权限不足（§20.4：骨架期只有 admin 角色能进管理与审计入口）。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super(UnifiedErrors.FORBIDDEN);
    }
}
