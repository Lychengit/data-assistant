package com.djzy.assistant.common.web.auth;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * 把 {@link UserContext} 挂在「当前线程」上的小盒子。
 *
 * <p>为什么用 ThreadLocal：一次 HTTP 请求从头到尾基本都在同一个线程上跑，
 * 控制器与 Service 想拿用户 id 直接拿，不必一层层当参数往下传。
 *
 * <p>为什么要小心：线程是**复用**的（Web 容器和线程池都会反复用同一条线程），
 * set 了不清理，上一个请求的用户就会泄漏给下一个请求。所以规矩是
 * **谁 set，谁负责在 finally 里 clear**：
 * <ul>
 *   <li>普通的 HTTP 请求由 {@link UserContextInterceptor} 统一清理，控制器不用管；</li>
 *   <li>自己开线程 / 丢线程池时，用 {@link #propagate(Runnable)} 包一层，
 *       它会在新线程里放好上下文、跑完再清干净（线程池不会自己继承 ThreadLocal）。</li>
 * </ul>
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {}

    /** 放入上下文；只允许拦截器或 {@link #propagate} 在明确的边界上调用。 */
    public static void set(UserContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    /** @return 当前线程上的上下文；不在请求线程里时为 null。 */
    public static UserContext get() {
        return CURRENT.get();
    }

    /** @return 一定不为 null；拿不到就是代码用错了地方，直接报错，不要静默放行。 */
    public static UserContext require() {
        UserContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("当前线程没有用户上下文：请确认请求经过了 UserContextInterceptor");
        }
        return context;
    }

    public static String requireUserId() {
        return require().userId();
    }

    /** 清掉当前线程的上下文：请求结束、或手动开线程跑完时必须调用。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 把「此刻的上下文」带到别的线程上去执行。
     *
     * <p>线程池的线程不会自动继承 ThreadLocal，所以要在**提交任务的那一边**（还在请求线程上）
     * 先用它包一层，再交给线程池。
     */
    public static Runnable propagate(Runnable task) {
        UserContext captured = CURRENT.get();
        return () -> {
            UserContext previous = CURRENT.get();
            set(captured);
            try {
                task.run();
            } finally {
                set(previous);
            }
        };
    }

    /**
     * {@link #propagate(Runnable)} 的「带一个入参、没有返回值」版本。
     *
     * <p>Reactor 的 {@code Flux.subscribe(onNext, onError, onComplete)} 要的就是这种形状；
     * 单独起个名字是为了避免和 {@link #propagate(Callable)} 在 lambda 上撞车（可读性也更好）。
     */
    public static <T> Consumer<T> propagateConsumer(Consumer<T> consumer) {
        UserContext captured = CURRENT.get();
        return value -> {
            UserContext previous = CURRENT.get();
            set(captured);
            try {
                consumer.accept(value);
            } finally {
                set(previous);
            }
        };
    }

    /** {@link #propagate(Runnable)} 的可返回值版本。 */
    public static <T> Callable<T> propagate(Callable<T> task) {
        UserContext captured = CURRENT.get();
        return () -> {
            UserContext previous = CURRENT.get();
            set(captured);
            try {
                return task.call();
            } finally {
                set(previous);
            }
        };
    }
}
