package com.coffeeorder.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 같은 작업을 여러 스레드에서 "진짜 동시에" 출발시킨다. 스레드를 만들기만 하면 먼저 만든 것부터 실행될 수 있어서,
 * 모든 스레드가 출발선(ready)에 선 것을 확인한 뒤 신호(start) 하나로 한꺼번에 보낸다.
 * 요청마다 걸린 시간과 예외를 모아 돌려준다 → 성공·의도한 실패·사고를 나눠 세고, 평균·p95·최대 시간을 잰다.
 */
public final class ConcurrentRunner {

    /** 요청 하나의 결과. error 가 null 이면 성공이다. */
    public record Outcome(long nanos, Throwable error) {
        public boolean success() {
            return error == null;
        }
    }

    public record Run(List<Outcome> outcomes, long totalNanos) {
    }

    private ConcurrentRunner() {
    }

    public static Run run(int threads, Callable<?> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long begin = System.nanoTime();
                    try {
                        task.call();
                        return new Outcome(System.nanoTime() - begin, null);
                    } catch (Throwable e) {
                        return new Outcome(System.nanoTime() - begin, e);
                    }
                }));
            }
            ready.await();
            long begin = System.nanoTime();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (Exception e) {
                    outcomes.add(new Outcome(TimeUnit.SECONDS.toNanos(60), e)); // 60초 안에 안 끝나면 사고(타임아웃)
                }
            }
            return new Run(outcomes, System.nanoTime() - begin);
        } finally {
            pool.shutdownNow();
        }
    }
}
