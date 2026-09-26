package com.djzy.assistant.common.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 打在控制器参数上，把当前登录用户注进来。
 *
 * <p>两种参数类型都支持：
 * <ul>
 *   <li>{@link UserContext}：要令牌、要链路 id 时用；</li>
 *   <li>{@code String}：只要 userId 时用。</li>
 * </ul>
 *
 * <p>有了它，控制器里就再也不用出现「解析 Authorization 头」那段样板代码。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {}
