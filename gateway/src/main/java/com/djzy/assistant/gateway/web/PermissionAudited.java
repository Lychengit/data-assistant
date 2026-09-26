package com.djzy.assistant.gateway.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 打在「这次调用要做权限判定」的接口方法上。
 *
 * <p>它本身什么都不做，只负责让 {@link PermissionAuditAspect} 知道「这个方法要记账」。
 * 好处是记账这件事有**唯一**归属：控制器里不再出现审计代码，也不会出现「某个分支忘了写审计」。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PermissionAudited {}
