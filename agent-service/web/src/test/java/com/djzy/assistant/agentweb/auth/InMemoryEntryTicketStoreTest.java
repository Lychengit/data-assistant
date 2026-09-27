package com.djzy.assistant.agentweb.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 券坑位的原子占有（§19.4）：**撞号时不许覆盖**。
 *
 * <p>券就是身份（流接口不验 JWT，身份全从券里取），所以「两张券共用一个键」不是概率问题而是正确性问题：
 * 后写的那张会把先写的顶掉，先写那张的持有者再去连流就会读到**别人的身份**。
 * 这里钉住的就是那一句：同一个键存第二次必须失败，且第一张券**原样有效**。
 */
class InMemoryEntryTicketStoreTest {

    private final InMemoryEntryTicketStore store = new InMemoryEntryTicketStore();

    @Test
    void 同一个键存第二次必须失败且不覆盖第一张() {
        Instant expiresAt = Instant.now().plusSeconds(60);
        EntryTicket alice = new EntryTicket("alice", "s1", "t1", expiresAt);

        assertThat(store.saveIfAbsent("collide", alice, 60)).isTrue();
        // 撞号：键上已经有人，第二次必须被拒。若这里返回 true 或把 alice 覆盖掉，就是「串号」的入口
        assertThat(store.saveIfAbsent("collide", new EntryTicket("bob", "s2", "t2", expiresAt), 60))
                .isFalse();

        assertThat(store.consume("collide", Instant.now())).contains(alice);
    }

    @Test
    void 券被消费之后坑位可以让给下一张() {
        Instant expiresAt = Instant.now().plusSeconds(60);

        assertThat(store.saveIfAbsent("slot", new EntryTicket("alice", "s1", "t1", expiresAt), 60))
                .isTrue();
        assertThat(store.consume("slot", Instant.now())).isPresent();

        // 券用过就没了，坑位空出来给后面的人（一次性只针对券本身，不是把这个键永久锁死）
        assertThat(store.saveIfAbsent("slot", new EntryTicket("bob", "s2", "t2", expiresAt), 60))
                .isTrue();
    }
}
