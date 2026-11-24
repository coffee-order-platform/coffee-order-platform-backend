package com.coffeeorder.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.concurrency.ConcurrentRunner.Outcome;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 동시 실행 도우미 자체의 약속을 DB 없이 확인한다: 결과를 다 모으고, 어떤 경로로 끝나도 작업이 끝난 뒤에야 돌아온다. */
class ConcurrentRunnerTest {

    @Test
    void 요청마다_성공과_예외를_결과로_모은다() throws Exception {
        AtomicInteger turn = new AtomicInteger();

        var outcomes = ConcurrentRunner.run(4, () -> {
            if (turn.getAndIncrement() % 2 == 0) {
                throw new IllegalStateException("실패");
            }
            return null;
        }).outcomes();

        assertThat(outcomes).hasSize(4);
        assertThat(outcomes.stream().filter(Outcome::success).count()).isEqualTo(2);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof IllegalStateException).count()).isEqualTo(2);
    }

    @Test
    void 기다리던_중_중단돼도_멈추라는_요청을_무시하는_작업이_끝난_뒤에야_중단을_알린다() throws Exception {
        // 진행 중인 JDBC 호출처럼 인터럽트를 무시하고, 테스트가 풀어 줄 때까지 도는 작업. 이 작업이 끝나기 전에 run() 이
        // 빠져나오면 호출자(@AfterEach 정리 등)가 아직 살아 있는 작업과 겹친다(CodeRabbit PR #16).
        // 시간이 아니라 신호(release)로 풀어서, 테스트 스레드가 늦게 돌아도 결과가 같다
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        Callable<Object> stubborn = () -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // 멈추라는 요청을 일부러 무시한다
                }
            }
            finished.incrementAndGet();
            return null;
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicInteger finishedWhenThrown = new AtomicInteger(-1);

        Thread caller = new Thread(() -> {
            try {
                ConcurrentRunner.run(3, stubborn);
            } catch (Throwable e) {
                finishedWhenThrown.set(finished.get());
                thrown.set(e);
            }
        });
        caller.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // 세 작업이 모두 돌기 시작한 뒤에 중단한다
        caller.interrupt();
        caller.join(300);
        assertThat(caller.isAlive()).as("작업이 살아 있는 동안에는 run() 이 돌아오지 않는다").isTrue();
        release.countDown();
        caller.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(finishedWhenThrown.get()).as("중단을 알릴 때 이미 끝난 작업 수").isEqualTo(3);
    }
}
