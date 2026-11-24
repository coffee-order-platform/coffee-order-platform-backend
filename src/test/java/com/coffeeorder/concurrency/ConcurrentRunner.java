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
        Throwable failure = null; // 진행 중이던 예외(중단 등). 종료 대기가 실패해도 가려지지 않게 붙여 보낸다
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
                    throw e; // 테스트 자체가 취소됐다 → 결과로 삼키지 않고 호출자에게 알린다(finally 가 작업 종료를 기다린 뒤)
                } catch (Exception e) {
                    outcomes.add(new Outcome(System.nanoTime() - begin, e)); // 60초 안에 안 끝나면 사고(타임아웃)
                }
            }
            return new Run(outcomes, System.nanoTime() - begin);
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            // 정상·시간 초과·중단 모든 경로에서, 돌려주거나 예외를 넘기기 전에 작업이 끝나길 기다린다.
            // 살아 있는 작업이 뒤늦게 커밋하면 다음 측정이나 @AfterEach 정리와 겹쳐 데이터를 오염시키기 때문이다.
            // shutdownNow 는 멈추라는 요청일 뿐이라(진행 중인 JDBC 호출은 무시할 수 있다) awaitTermination 으로 확인한다.
            // 한계: 60초 안에 안 끝나거나 이 대기 중에 다시 중단되면 살아 있는 작업이 남을 수 있다. 그때는 결과를 믿을 수 없어 실패시킨다.
            // 이 60초 경로는 테스트가 60초 넘게 걸려 따로 테스트하지 않는다
            pool.shutdownNow();
            if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                IllegalStateException stuck = new IllegalStateException("끝나지 않은 작업이 남아 결과를 믿을 수 없다");
                if (failure != null) {
                    stuck.addSuppressed(failure);
                }
                throw stuck;
            }
        }
    }
}
