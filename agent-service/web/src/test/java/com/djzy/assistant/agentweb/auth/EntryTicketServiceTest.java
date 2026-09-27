package com.djzy.assistant.agentweb.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 发券的「抢坑位」语义（§19.4）：撞号要**换一张重发**，而不是覆盖已有记录。
 *
 * <p>随机串够长只是把撞号概率压小，所以"撞了会怎样"必须能测。这里用可控的假存储把撞号逼出来，
 * 钉住两条路：① 撞了就重试，重试那张照样按正常的「占坑」语义写进去；
 * ② 连撞满了就硬失败，绝不发出可能重号的券。
 */
class EntryTicketServiceTest {

    /** 前 {@code failFirst} 次占坑失败（模拟撞号），之后成功；并记录占坑被调用了几次。 */
    private static final class CollidingStore implements EntryTicketStore {

        private final int failFirst;
        private final AtomicInteger attempts = new AtomicInteger();
        private EntryTicket saved;

        private CollidingStore(int failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public boolean saveIfAbsent(String ticketHash, EntryTicket ticket, long ttlSeconds) {
            if (attempts.incrementAndGet() <= failFirst) {
                return false;
            }
            saved = ticket;
            return true;
        }

        @Override
        public Optional<EntryTicket> consume(String ticketHash, Instant now) {
            return Optional.empty();
        }
    }

    @Test
    void 撞号就换一张重发而不是覆盖() {
        CollidingStore store = new CollidingStore(1);
        EntryTicketService service = new EntryTicketService(store, Duration.ofSeconds(60));

        EntryTicketService.IssuedTicket issued = service.issue("alice", "s1", "t1");

        assertThat(store.attempts).hasValue(2);
        assertThat(issued.ticket()).isNotBlank();
        // 存进去的那条必须是这次发出去的身份，不能被别的券改写
        assertThat(store.saved.userId()).isEqualTo("alice");
        assertThat(store.saved.sessionId()).isEqualTo("s1");
        assertThat(store.saved.turnId()).isEqualTo("t1");
    }

    @Test
    void 连撞满就硬失败不发出可能重号的券() {
        CollidingStore store = new CollidingStore(Integer.MAX_VALUE);
        EntryTicketService service = new EntryTicketService(store, Duration.ofSeconds(60));

        assertThatThrownBy(() -> service.issue("alice", "s1", "t1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.attempts).hasValue(3);
    }
}
