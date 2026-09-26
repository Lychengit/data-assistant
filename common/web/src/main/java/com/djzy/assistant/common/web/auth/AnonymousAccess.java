package com.djzy.assistant.common.web.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「这个接口不需要登录身份」。
 *
 * <p>默认是**全都要身份**（fail-closed），只有确实靠别的凭证进门的接口才打这个注解，
 * 例如 SSE 连接是拿一次性券建立的，不需要再验一次 JWT。
 *
 * <p>可以打在方法上，也可以打在控制器类上（整个控制器都放开）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AnonymousAccess {}
