package com.djzy.assistant.common.web.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 用户上下文的小盒子必须同时做到两件事：**传得到**（跨线程）与**清得掉**（不留残渣）。
 *
 * <p>这两条都是出过事故的类型：传不到 → 跨线程的身份丢失（拿不到令牌 / 拿错人）；
 * 清不掉 → 线程复用时把上一个请求的用户泄漏给下一个请求。
 *
 * <p>用**单线程**的线程池：第二次提交的任务会落在同一条线程上，正好可以验证
 * 「传播用完必须清干净」——这正是 web 容器线程复用的真实形态。
 */
class UserContextHolderTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        UserContextHolder.clear();
    }

    @Test
    void 没有上下文时取值是null而不是抛错() {
        assertNull(UserContextHolder.get());
    }

    @Test
    void 没有上下文时require直接报错不静默放行() {
        IllegalStateException error = assertThrows(IllegalStateException.class, UserContextHolder::require);

        assertTrue(error.getMessage().contains("UserContextInterceptor"));
    }

    @Test
    void 清理之后当前线程再也取不到这个人() {
        UserContextHolder.set(UserContext.of("alice"));

        UserContextHolder.clear();

        assertNull(UserContextHolder.get());
    }

    @Test
    void 线程池的任务能拿到提交那一刻的身份() throws Exception {
        UserContextHolder.set(UserContext.of("alice").withToken("t-alice"));

        AtomicReference<String> seenUser = new AtomicReference<>();
        AtomicReference<String> seenToken = new AtomicReference<>();
        pool.submit(UserContextHolder.propagate(() -> {
                    seenUser.set(UserContextHolder.require().userId());
                    seenToken.set(UserContextHolder.require().bearerToken());
                }))
                .get(5, TimeUnit.SECONDS);

        assertEquals("alice", seenUser.get());
        assertEquals("t-alice", seenToken.get());
    }

    /** 关键的一条：传播跑完之后，池子里的线程必须回到「没有上下文」的状态。 */
    @Test
    void 传播跑完之后池子里的线程不留残渣() throws Exception {
        UserContextHolder.set(UserContext.of("alice"));
        pool.submit(UserContextHolder.propagate(
                        () -> assertEquals("alice", UserContextHolder.requireUserId())))
                .get(5, TimeUnit.SECONDS);

        // 同一个池子里的下一条任务没有包 propagate：它必须看不到任何身份
        AtomicReference<UserContext> leftover = new AtomicReference<>();
        pool.submit(() -> leftover.set(UserContextHolder.get())).get(5, TimeUnit.SECONDS);

        assertNull(leftover.get());
    }

    /** 传播不该动「提交者」自己线程上的上下文。 */
    @Test
    void 传播不影响原线程的上下文() throws Exception {
        UserContextHolder.set(UserContext.of("alice"));

        pool.submit(UserContextHolder.propagate(() -> UserContextHolder.set(UserContext.of("bob"))))
                .get(5, TimeUnit.SECONDS);

        assertEquals("alice", UserContextHolder.require().userId());
    }

    @Test
    void 提交时就没有上下文则新线程里也是没有() throws Exception {
        AtomicReference<UserContext> seen = new AtomicReference<>();
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        pool.submit(UserContextHolder.propagate(() -> {
                    seen.set(UserContextHolder.get());
                    ran.set(true);
                }))
                .get(5, TimeUnit.SECONDS);

        assertEquals(true, ran.get());
        assertNull(seen.get());
    }
}
