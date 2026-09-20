package com.djzy.assistant.common.api;

import jakarta.validation.Valid;
import java.util.Objects;

/**
 * 网关 → 接口服务的统一信封：{@code { caller: {...}, args: {...} }}。
 *
 * <p>为什么要泛型而不是"统一 Map"：接口的入参本来就该是有类型的对象——有了类型，Spring 直接
 * 反序列化，方法里不用再手写一遍 {@code parse(body)}，参数名写错在编译期就暴露，{@code @Valid}
 * 也能直接挂在 {@code args} 上（§18.4.5 I3）。
 *
 * <p>为什么身份不能跟业务参数平铺在顶层：平铺就得靠 {@code @JsonIgnoreProperties} 之类的约定
 * 把 {@code userId} 从业务字段里摘出去，一旦某个接口真有叫 {@code userId} 的业务参数就撞车。
 * 分成 caller / args 两层，边界是结构性的，不靠约定。
 *
 * <p>两个字段都标了 {@code @Valid}：控制器上写一次 {@code @Valid @RequestBody ApiEnvelope<T>}，
 * 校验就会级联到业务参数自己的约束注解上——各接口用自己的 DTO 声明规则，不再手写参数白名单（§18.4.5 I3）。
 *
 * @param caller 网关注入的可信身份（在签名体内，改不了）
 * @param args 该接口自己的业务入参（有类型）
 * @param <T> 业务入参类型
 */
public record ApiEnvelope<T>(@Valid CallerInfo caller, @Valid T args) {

    public ApiEnvelope {
        Objects.requireNonNull(caller, "caller");
    }
}