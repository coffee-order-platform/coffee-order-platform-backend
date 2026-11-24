package com.coffeeorder.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.concurrency.ConcurrentRunner.Outcome;
import com.coffeeorder.concurrency.ConcurrentRunner.Run;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동시성 실험 A(노션 동시성 실험 가이드 2장): 잔액 100,000 에서 1,000 차감 100건을 동시에 보내고 락 방식 6가지를 비교한다.
 * 운영 코드가 아니라 실험용 표(exp_points, exp_histories)에서 같은 틀로 "잠그는 방법"만 바꿔 잰다 → 차이는 락 방식에서만 나온다.
 * 평소 테스트에서는 건너뛴다. 실행: EXPERIMENT=true ./gradlew test --tests '*LockExperimentTest'
 * 결과: build/experiment/lock-experiment.csv (회차별 원자료) + 콘솔 요약(방식별 5회 가운데 값)
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EXPERIMENT", matches = "true")
class LockExperimentTest {

    private static final int REQUESTS = 100;
    private static final long START_BALANCE = 100_000;
    private static final long AMOUNT = 1_000;
    private static final int ROUNDS = 5;
    private static final String REDIS_LOCK_KEY = "exp:lock:point:1";
    private static final RedisScript<Long> UNLOCK = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StringRedisTemplate redis;

    /** 잔액 부족·낙관적 충돌처럼 "규칙대로 거절된" 실패. 이 밖의 예외(데드락, 연결 대기 초과 등)는 사고로 센다. */
    static class IntendedFailure extends RuntimeException {
        IntendedFailure(String reason) {
            super(reason, null, false, false);
        }
    }

    interface Strategy {
        void deduct();
    }

    @BeforeAll
    void 실험용_표() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS exp_points (id BIGINT PRIMARY KEY, balance BIGINT NOT NULL, version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS exp_histories (id BIGINT AUTO_INCREMENT PRIMARY KEY, point_id BIGINT NOT NULL, "
                + "amount BIGINT NOT NULL, balance_after BIGINT NOT NULL)");
    }

    @AfterAll
    void 실험용_표_삭제() {
        jdbc.execute("DROP TABLE IF EXISTS exp_histories");
        jdbc.execute("DROP TABLE IF EXISTS exp_points");
        redis.delete(REDIS_LOCK_KEY);
    }

    @Test
    void 락_방식_6가지_정확도와_대가_비교() throws Exception {
        Map<String, Strategy> strategies = new java.util.LinkedHashMap<>();
        strategies.put("락 없음", this::noLock);
        strategies.put("비관적 락", this::pessimistic);
        strategies.put("낙관적 락 (재시도 없음)", () -> optimistic(0));
        strategies.put("낙관적 락 (재시도 3회)", () -> optimistic(3));
        strategies.put("원자적 UPDATE", this::atomicUpdate);
        strategies.put("Redis 락", this::redisLock);

        List<String> csv = new ArrayList<>();
        csv.add("method,round,final_balance,success,intended_fail,accident,histories,total_ms,avg_ms,p95_ms,max_ms,accident_types");
        List<String> summary = new ArrayList<>();
        List<Map.Entry<String, Result>> all = new ArrayList<>();
        for (Map.Entry<String, Strategy> entry : strategies.entrySet()) {
            measure(entry.getValue()); // 예열 1회는 버린다(첫 실행은 클래스 로딩·연결 생성 때문에 느리다)
            List<long[]> rounds = new ArrayList<>();
            for (int round = 1; round <= ROUNDS; round++) {
                Result r = measure(entry.getValue());
                all.add(Map.entry(entry.getKey(), r));
                csv.add(String.join(",", entry.getKey(), Integer.toString(round), Long.toString(r.finalBalance),
                        Integer.toString(r.success), Integer.toString(r.intended), Integer.toString(r.accident),
                        Long.toString(r.histories), ms(r.totalNanos), ms(r.avgNanos), ms(r.p95Nanos), ms(r.maxNanos),
                        "\"" + r.accidentTypes + "\""));
                rounds.add(new long[] {r.totalNanos, r.finalBalance, r.success, r.intended, r.accident, r.histories,
                        r.avgNanos, r.p95Nanos, r.maxNanos});
            }
            rounds.sort(Comparator.comparingLong(a -> a[0]));
            long[] mid = rounds.get(ROUNDS / 2); // 전체 시간 기준 가운데 회차
            summary.add(String.format("%-16s 잔액 %6d | 성공 %3d | 의도한 실패 %3d | 사고 %3d | 내역 %3d | 전체 %7s ms | 평균 %7s | p95 %7s | 최대 %7s",
                    entry.getKey(), mid[1], mid[2], mid[3], mid[4], mid[5], ms(mid[0]), ms(mid[6]), ms(mid[7]), ms(mid[8])));
        }

        // 기준선: 동시가 아니라 한 스레드로 100건을 차례로 처리한다. 동시 실행 시간이 이것과 비슷하면 병목은 락이 아니라 커밋 자체다
        List<Long> sequential = new ArrayList<>();
        for (int round = 0; round <= ROUNDS; round++) {
            measure(() -> { });
            long begin = System.nanoTime();
            for (int i = 0; i < REQUESTS; i++) {
                pessimistic();
            }
            if (round > 0) { // 0회차는 예열
                sequential.add(System.nanoTime() - begin);
            }
        }
        sequential.sort(null);
        summary.add(String.format("%-16s 한 스레드로 %d건 차례로: 전체 %s ms (건당 %s ms)", "기준선(순차)", REQUESTS,
                ms(sequential.get(ROUNDS / 2)), ms(sequential.get(ROUNDS / 2) / REQUESTS)));

        Path out = Path.of("build", "experiment", "lock-experiment.csv");
        Files.createDirectories(out.getParent());
        Files.write(out, csv);
        System.out.println("\n===== 실험 A 결과 (요청 " + REQUESTS + "건, 방식별 " + ROUNDS + "회 중 가운데 값, DB 연결 최대 10개) =====");
        summary.forEach(System.out::println);
        System.out.println("원자료: " + out.toAbsolutePath());

        // 측정이 믿을 만한지 최소 확인(결과를 다 남긴 뒤에 본다): 모든 방식에서 사고 0건, 내역 수 = 성공 수.
        // 락 없음은 덮어쓰기로 잔액이 틀리는 것을 보여 주는 대조군이라 잔액 검사에서 뺀다
        for (Map.Entry<String, Result> e : all) {
            Result r = e.getValue();
            assertThat(r.accident).as(e.getKey() + " 사고 " + r.accidentTypes).isZero();
            assertThat(r.histories).as(e.getKey() + " 내역 수").isEqualTo(r.success);
            if (!e.getKey().equals("락 없음")) {
                assertThat(r.finalBalance).as(e.getKey() + " 잔액").isEqualTo(START_BALANCE - r.success * AMOUNT);
            }
            // 잔액이 딱 맞게 충분하므로(100건 × 1,000 = 100,000) 충돌 거절이 없는 방식은 전부 성공해야 한다.
            // 낙관적 락만 충돌을 거절로 돌려주는 방식이라 거절을 허용한다(CodeRabbit PR #16)
            if (!e.getKey().startsWith("낙관적")) {
                assertThat(r.success).as(e.getKey() + " 성공 수").isEqualTo(REQUESTS);
                assertThat(r.intended).as(e.getKey() + " 의도한 실패").isZero();
            } else {
                // 충돌 거절은 허용하지만, 같은 버전을 읽은 요청 중 하나는 반드시 이긴다 → 전부 거절이면 버전 조건이 깨진 것
                assertThat(r.success).as(e.getKey() + " 성공 수").isPositive();
            }
        }
    }

    // ---- 측정 ----

    record Result(long finalBalance, int success, int intended, int accident, long histories,
                  long totalNanos, long avgNanos, long p95Nanos, long maxNanos, String accidentTypes) {
    }

    private Result measure(Strategy strategy) throws InterruptedException, IOException {
        jdbc.update("DELETE FROM exp_histories");
        jdbc.update("DELETE FROM exp_points");
        jdbc.update("INSERT INTO exp_points (id, balance, version) VALUES (1, ?, 0)", START_BALANCE);
        redis.delete(REDIS_LOCK_KEY);

        Run run = ConcurrentRunner.run(REQUESTS, () -> {
            strategy.deduct();
            return null;
        });

        List<Outcome> outcomes = run.outcomes();
        int success = (int) outcomes.stream().filter(Outcome::success).count();
        int intended = (int) outcomes.stream().filter(o -> o.error() instanceof IntendedFailure).count();
        int accident = outcomes.size() - success - intended;
        String accidentTypes = outcomes.stream().filter(o -> !o.success() && !(o.error() instanceof IntendedFailure))
                .map(o -> o.error().getClass().getSimpleName()).distinct().sorted().reduce((a, b) -> a + " " + b).orElse("");
        long[] nanos = outcomes.stream().mapToLong(Outcome::nanos).sorted().toArray();
        // 끝난 뒤 DB 를 다시 읽는다: 응답이 전부 성공이어도 잔액이 틀릴 수 있다
        long balance = jdbc.queryForObject("SELECT balance FROM exp_points WHERE id = 1", Long.class);
        long histories = jdbc.queryForObject("SELECT COUNT(*) FROM exp_histories", Long.class);
        return new Result(balance, success, intended, accident, histories, run.totalNanos(),
                (long) Arrays.stream(nanos).average().orElse(0), nanos[(int) Math.ceil(nanos.length * 0.95) - 1],
                nanos[nanos.length - 1], accidentTypes);
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    // ---- 방식 6가지: 모두 "잔액 확인 → 차감 → 내역 저장"을 한 트랜잭션으로 한다 ----

    /** 읽기 → 계산 → 쓰기 사이에 다른 요청이 끼어들 수 있다(Lost Update). */
    private void noLock() {
        tx.executeWithoutResult(s -> {
            long balance = jdbc.queryForObject("SELECT balance FROM exp_points WHERE id = 1", Long.class);
            write(balance);
        });
    }

    /** 지금 운영 코드의 방식(findByUserIdForUpdate). 행을 먼저 잠그고 다른 요청은 기다린다. */
    private void pessimistic() {
        tx.executeWithoutResult(s -> {
            long balance = jdbc.queryForObject("SELECT balance FROM exp_points WHERE id = 1 FOR UPDATE", Long.class);
            write(balance);
        });
    }

    /** 잠그지 않고, 저장할 때 내가 읽은 version 이 그대로일 때만 바꾼다. 바뀌었으면 충돌 → retries 번까지 처음부터 다시. */
    private void optimistic(int retries) {
        for (int attempt = 0; ; attempt++) {
            try {
                tx.executeWithoutResult(s -> {
                    Map<String, Object> row = jdbc.queryForMap("SELECT balance, version FROM exp_points WHERE id = 1");
                    long balance = ((Number) row.get("balance")).longValue();
                    long version = ((Number) row.get("version")).longValue();
                    if (balance < AMOUNT) {
                        throw new IntendedFailure("잔액 부족");
                    }
                    int updated = jdbc.update("UPDATE exp_points SET balance = ?, version = version + 1 WHERE id = 1 AND version = ?",
                            balance - AMOUNT, version);
                    if (updated == 0) {
                        throw new IntendedFailure("낙관적 충돌");
                    }
                    jdbc.update("INSERT INTO exp_histories (point_id, amount, balance_after) VALUES (1, ?, ?)", AMOUNT, balance - AMOUNT);
                });
                return;
            } catch (IntendedFailure e) {
                if (!"낙관적 충돌".equals(e.getMessage()) || attempt >= retries) {
                    throw e;
                }
            }
        }
    }

    /** 확인과 차감을 DB 한 문장으로 한다. 바뀐 행이 0이면 잔액 부족. 처리 후 잔액은 같은 트랜잭션에서 내가 쓴 값을 읽는다. */
    private void atomicUpdate() {
        tx.executeWithoutResult(s -> {
            int updated = jdbc.update("UPDATE exp_points SET balance = balance - ? WHERE id = 1 AND balance >= ?", AMOUNT, AMOUNT);
            if (updated == 0) {
                throw new IntendedFailure("잔액 부족");
            }
            long after = jdbc.queryForObject("SELECT balance FROM exp_points WHERE id = 1", Long.class);
            jdbc.update("INSERT INTO exp_histories (point_id, amount, balance_after) VALUES (1, ?, ?)", AMOUNT, after);
        });
    }

    /**
     * DB 밖(Redis)에 "사용 중" 표시를 건다: SET key token NX PX 3000 = 없을 때만, 3초 뒤 자동 해제.
     * 못 잡으면 5ms 쉬고 다시(최대 30초, 넘으면 사고). DB 는 잠그지 않고, 커밋이 끝난 뒤 내가 건 표시만 지운다.
     */
    private void redisLock() {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(REDIS_LOCK_KEY, token, Duration.ofSeconds(3)))) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Redis 락 대기 30초 초과");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        try {
            noLock(); // 잠금은 Redis 가 맡으므로 DB 쿼리는 락 없는 방식과 같다
        } finally {
            redis.execute(UNLOCK, List.of(REDIS_LOCK_KEY), token);
        }
    }

    /** 락 없음·비관적 락·Redis 락이 같이 쓰는 마무리: 잔액 확인 → 차감 → 내역. */
    private void write(long balance) {
        if (balance < AMOUNT) {
            throw new IntendedFailure("잔액 부족");
        }
        jdbc.update("UPDATE exp_points SET balance = ? WHERE id = 1", balance - AMOUNT);
        jdbc.update("INSERT INTO exp_histories (point_id, amount, balance_after) VALUES (1, ?, ?)", AMOUNT, balance - AMOUNT);
    }
}
