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
            long deadline = begin + TimeUnit.SECONDS.toNanos(60); // 전체가 60초 안에 끝나야 한다(요청마다 60초가 아니다)
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    throw e; // 테스트 자체가 취소됐다 → 결과로 삼키지 않고 호출자에게 알린다(finally 가 작업에 멈추라고 요청한다)
                } catch (Exception e) {
                    outcomes.add(new Outcome(System.nanoTime() - begin, e)); // 60초 안에 안 끝나면 사고(타임아웃)
                }
            }
            long totalNanos = System.nanoTime() - begin;
            // 시간 초과된 작업은 아직 돌고 있을 수 있다. 반환 뒤에 커밋하면 다음 측정·테스트의 데이터를 오염시키므로,
            // 끝날 때까지 기다리고 그래도 안 끝나면 결과를 믿을 수 없어 멈춘다(MySQL 잠금 대기는 innodb_lock_wait_timeout 50초에서 끝난다)
            pool.shutdownNow();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("시간 초과된 작업이 끝나지 않아 결과를 믿을 수 없다");
            }
            return new Run(outcomes, totalNanos);
        } finally {
            pool.shutdownNow();
        }
    }
}
