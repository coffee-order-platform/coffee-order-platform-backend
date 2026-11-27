package com.coffeeorder.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 부하 실험(노션 성능·동작 실험 EXP-2): k6 가상 사용자 50명이 실제 API 로 "담기 → 주문"을 반복하는 4가지 상황(11/27 경화님 결정).
 *   기본       : 50명이 각자 자기 계좌. 잔액 충분
 *   잔액 바닥   : 사람마다 잔액 = 주문 5번치 + (단가 − 1). 6번째부터 잔액 부족이 정상
 *   여러 기기   : 계좌 25개에 2명씩(한 사람이 휴대폰으로 주문하는 동안 PC 로 충전 · 환불). 50명이 한 계좌는 현실에 없어 이렇게 바꿈
 *   인기 메뉴   : 기본과 같되 모두 아메리카노만
 * 마지막에 기본을 한 번 더 돌려 대조군으로 쓴다(처음과 15% 넘게 다르면 측정 환경이 흔들린 것).
 * 측정 회차마다 끝난 뒤 DB 와 Redis 를 대조한다: 잔액 = 시작 + 충전 − 사용 + 환불, 잔액 ≥ 0, 마지막 내역의 잔액 = 현재 잔액,
 * 사용 합 = 주문 금액 합, 순차 환불에서 같은 주문 중복 환불 없음(같은 주문 동시 환불은 ConcurrencyRegressionTest R-8 이 본다), k6 가 센 성공 수 = DB 행 수, Redis 인기 메뉴 = 결제 상태 주문 수량 합(여러 기기 포함, 11/27 결정).
 * 장바구니 충돌(409)과 예상 밖 응답은 측정을 멈추지 않고 끝까지 센 뒤, 결과 파일을 쓰고 나서 테스트를 실패시킨다(11/27 결정).
 * 시계를 2098-01-15(서울)로 고정해 다른 테스트 데이터와 겹치지 않게 하고, 실험 사용자(이름 EXP2-)는 시작 · 끝에 통째로 지운다.
 * 테스트 설정이라 coffee_order_test 스키마와 Redis 1번 DB 를 쓴다(개발 DB 에 쓰지 않음). 평소 테스트에서는 건너뛴다.
 * k6 가 PATH 에 있어야 한다(없으면 K6_BIN 에 실행 파일 경로). Gradle 은 환경 변수만 바뀐 실행을 최신으로 보고 건너뛰므로 --rerun-tasks.
 * 실행: EXPERIMENT=true ./gradlew test --tests '*OrderLoadExperimentTest' --rerun-tasks -i
 * 결과: build/experiment/order-load-experiment.txt + 콘솔(-i), k6 요약 JSON 은 build/experiment/exp2-*.json
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.jpa.show-sql=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EXPERIMENT", matches = "true")
class OrderLoadExperimentTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2098, 1, 15);
    private static final String REDIS_KEY = "popular:menu:" + TODAY.format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String NAME_PREFIX = "EXP2-";
    private static final int USERS = 50; // = k6 가상 사용자 수
    private static final int RUNS = 3; // 측정 반복(중앙값). 홀수
    private static final String K6_WARMUP = "10s";
    // 1·2차 실행에서 처음 2~3분만 빠르고 들쭉날쭉하다가 안정됐다(대조군 31 · 36%). 측정 전에 기본 상황을 이만큼 돌려 버린다(11/27 경화님 결정)
    private static final String K6_LONG_WARMUP = "3m";
    private static final int K6_TIMEOUT_MINUTES = 5;
    private static final String SQL_ERROR_LOGGER = "org.hibernate.orm.jdbc.error"; // 교착(1213)이 나면 Hibernate 가 여기에 남긴다
    private static final String K6_DURATION = "30s";
    private static final int CONTROL_DRIFT_PERCENT = 15;
    private static final long RICH = 1_000_000_000L; // 30초 동안 써도 바닥나지 않는 잔액
    private static final int FLOOR_ORDERS = 5; // 잔액 바닥: 사람마다 성공할 수 있는 주문 수
    private static final int K6_THRESHOLD_FAILED = 99; // k6 가 임계값(checks)이 깨졌을 때 내는 종료 코드
    private static final Pattern K6_LINE = Pattern.compile("order_tps=([\\d.]+) order_avg=([\\d.]+)ms "
            + "order_p95=([\\d.]+)ms rejected_tps=([\\d.]+) rejected_p95=([\\d.]+)ms order_ok=(\\d+) "
            + "order_rejected=(\\d+) cart_conflict=(\\d+) charge_ok=(\\d+) refund_ok=(\\d+) unexpected=(\\d+) "
            + "client_fail=(\\d+) reject_close=(\\d+)");

    /** mode 는 k6 스크립트의 MODE, accounts 는 쓰는 계좌 수(여러 기기는 계좌 하나에 VU 2명). */
    private record Scenario(String mode, String label, int accounts) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("basic", "기본", USERS),
            new Scenario("floor", "잔액 바닥", USERS),
            new Scenario("multi-device", "여러 기기", USERS / 2),
            new Scenario("hot-menu", "인기 메뉴", USERS),
            new Scenario("basic", "기본(대조)", USERS));

    private record K6(double orderTps, double orderAvgMs, double orderP95Ms, double rejectedTps, double rejectedP95Ms,
            long orderOk, long orderRejected, long cartConflict, long chargeOk, long refundOk, long unexpected,
            long clientFail, long rejectClose) {}

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2098-01-15T03:00:00Z"), SEOUL); // 서울 12:00
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired DataSource dataSource;

    private final List<Long> users = new ArrayList<>();
    private final Map<Long, Long> startBalance = new HashMap<>();
    private List<Long> menus;
    private Map<Long, Long> prices;

    @BeforeAll
    void 준비() throws IOException {
        deleteExperimentUsers(); // 이전 실행이 강제 종료돼 남긴 것
        for (int i = 1; i <= USERS; i++) {
            String name = NAME_PREFIX + i;
            users.add(tx.execute(status -> {
                User user = userRepository.save(new User(name));
                pointRepository.save(new Point(user, RICH));
                return user.getId();
            }));
        }
        menus = List.of(menuId("아메리카노"), menuId("폭신폭신 구름과자 라떼"), menuId("졸린 곰돌이 우유"),
                menuId("번개맞은 바닐라"), menuId("달빛 두리안 프라푸치노"));
        prices = new HashMap<>();
        menus.forEach(id -> prices.put(id, jdbc.queryForObject("SELECT price FROM menus WHERE id = ?", Long.class, id)));
        Files.createDirectories(Path.of("build", "experiment")); // k6 는 요약 JSON 의 폴더를 만들어 주지 않는다
    }

    @AfterAll
    void 정리() {
        deleteExperimentUsers();
    }

    @Test
    void 주문_부하_4가지_상황_k6() throws Exception {
        List<String> report = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        report.add("[EXP-2] k6 가상 사용자 " + USERS + "명 " + K6_DURATION + " × " + RUNS + "회(중앙값, 상황마다 버리는 k6 예열 "
                + K6_WARMUP + "). 담기(수량 1) → 주문 반복, 메뉴는 사람마다 5종 중 하나");
        report.add("  조건: DB 연결 풀 최대 " + ((HikariDataSource) dataSource).getMaximumPoolSize()
                + "개(기본값), 서버는 테스트 JVM 안, MySQL·Redis(Docker)·k6 모두 같은 PC, 주문마다 주문 데이터 전송 로그 1줄");
        // 장바구니 담기 교착은 재시도로 복구돼 응답에 드러나지 않는다. 서버 로그를 받아 회차마다 센다
        ListAppender<ILoggingEvent> sqlErrors = new ListAppender<>();
        sqlErrors.start();
        Logger sqlErrorLogger = (Logger) LoggerFactory.getLogger(SQL_ERROR_LOGGER);
        sqlErrorLogger.addAppender(sqlErrors);
        try {
            measure(report, problems, sqlErrors);
        } finally { // 도중에 실패해도 같은 JVM 의 다음 테스트에 어펜더가 남지 않게(리뷰)
            sqlErrorLogger.detachAppender(sqlErrors);
        }
        report.add(problems.isEmpty() ? "정합성: 모든 회차 통과" : "정합성 · 발견 " + problems.size() + "건:");
        problems.forEach(p -> report.add("  - " + p));
        report.forEach(System.out::println);
        Files.write(Path.of("build", "experiment", "order-load-experiment.txt"), report);
        assertThat(problems).as("정합성 문제 · 발견(위 결과 파일 참고)").isEmpty();
    }

    private void measure(List<String> report, List<String> problems, ListAppender<ILoggingEvent> sqlErrors)
            throws IOException, InterruptedException {
        reset(SCENARIOS.getFirst(), users);
        sqlErrors.list.clear();
        warmup(SCENARIOS.getFirst(), users, K6_LONG_WARMUP, report, problems, sqlErrors);
        List<Double> basicTps = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            List<Long> ids = users.subList(0, s.accounts);
            reset(s, ids);
            sqlErrors.list.clear();
            warmup(s, ids, K6_WARMUP, report, problems, sqlErrors); // 동시 사용자 50명 상태(스레드 · 연결 풀 · JIT)를 데우고 버린다
            List<K6> runs = new ArrayList<>();
            List<Long> deadlocks = new ArrayList<>();
            for (int r = 1; r <= RUNS; r++) {
                reset(s, ids);
                sqlErrors.list.clear();
                String label = s.label + " " + r + "회차";
                K6 k6 = runK6(s, ids, K6_DURATION, "build/experiment/exp2-" + s.mode
                        + (s.label.contains("대조") ? "-control" : "") + "-" + r + ".json");
                problems.addAll(verify(s, ids, k6, label));
                runs.add(k6);
                deadlocks.add(deadlocks(sqlErrors));
            }
            double tps = median(runs, K6::orderTps);
            if (s.mode.equals("basic")) {
                basicTps.add(tps);
            }
            String line = String.format("%-7s | 성공 주문/초 %6.1f | 주문 평균 %7.2fms p95 %7.2fms | 성공 주문/초 3회 %s",
                    s.label, tps, median(runs, K6::orderAvgMs), median(runs, K6::orderP95Ms),
                    runs.stream().map(k -> String.format("%.1f", k.orderTps)).toList());
            if (s.mode.equals("floor")) {
                line += String.format(" | 잔액 부족 거절/초 %.1f p95 %.2fms | 거절 중 연결 끊음 %s / %s건",
                        median(runs, K6::rejectedTps), median(runs, K6::rejectedP95Ms),
                        runs.stream().map(K6::rejectClose).toList(), runs.stream().map(K6::orderRejected).toList());
            }
            if (s.mode.equals("multi-device")) {
                line += String.format(" | 충전 %s건 · 환불 %s건(회차별)", runs.stream().map(K6::chargeOk).toList(),
                        runs.stream().map(K6::refundOk).toList());
            }
            line += String.format(" | 교착(재시도로 복구 포함) %s건 · 장바구니 충돌 %s건 · 예상 밖 %s건 · k6 연결 실패(서버 도달 전) %s건",
                    deadlocks, runs.stream().map(K6::cartConflict).toList(), runs.stream().map(K6::unexpected).toList(),
                    runs.stream().map(K6::clientFail).toList());
            report.add(line);
        }
        double drift = Math.abs(basicTps.get(1) - basicTps.get(0)) / basicTps.get(0) * 100;
        report.add(String.format("대조군(기본 처음 ↔ 마지막) 변동 %.1f%% → %s", drift, drift <= CONTROL_DRIFT_PERCENT
                ? "이 실행 안의 상황끼리 비교에만 유효(±" + CONTROL_DRIFT_PERCENT + "% 이내). 절대 수치는 실행마다 달라 인용하지 않는다"
                : "비교 불가: 측정 환경이 흔들림. 이 실행의 수치는 인용하지 말고 다시 실행"));
    }

    /** 예열은 수치를 버리지만, 장바구니 충돌 · 예상 밖 응답 · 교착은 남긴다(끝까지 재고 발견으로 표시, 리뷰). DB 정합성은 측정 회차만 본다. */
    private void warmup(Scenario s, List<Long> ids, String duration, List<String> report, List<String> problems,
            ListAppender<ILoggingEvent> sqlErrors) throws IOException, InterruptedException {
        K6 k6 = runK6(s, ids, duration, null);
        report.add(String.format("  예열 %s %s(버림) | 교착(재시도로 복구 포함) %d건 · 장바구니 충돌 %d건 · 예상 밖 %d건 · "
                + "k6 연결 실패(서버 도달 전) %d건", s.label, duration, deadlocks(sqlErrors), k6.cartConflict,
                k6.unexpected, k6.clientFail));
        String label = s.label + " 예열 " + duration;
        check(problems, k6.unexpected == 0, label + ": 예상 밖 응답 " + k6.unexpected + "건(콘솔의 k6 로그 참고)");
        check(problems, k6.cartConflict == 0, label + ": 발견 - 각자 계좌인데 장바구니 충돌(409) " + k6.cartConflict + "건");
    }

    private static long deadlocks(ListAppender<ILoggingEvent> sqlErrors) {
        return sqlErrors.list.stream().filter(e -> e.getFormattedMessage().contains("ErrorCode: 1213")).count();
    }

    // ---- 데이터 ----

    /** 회차마다 실험 사용자의 주문 · 내역 · 장바구니를 지우고 시작 잔액을 다시 넣는다. 사용자 행은 남겨 id 를 고정한다. */
    private void reset(Scenario s, List<Long> ids) {
        String in = in(users);
        jdbc.update("DELETE FROM point_histories WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (" + in + "))");
        jdbc.update("DELETE FROM orders WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM cart_items WHERE user_id IN (" + in + ")");
        startBalance.clear();
        for (int i = 0; i < ids.size(); i++) {
            long balance = RICH;
            if (s.mode.equals("floor")) {
                long price = prices.get(menuOf(s, i));
                balance = price * FLOOR_ORDERS + price - 1; // 5번은 되고 6번째는 1원 모자람
            }
            startBalance.put(ids.get(i), balance);
            jdbc.update("UPDATE points SET balance = ? WHERE user_id = ?", balance, ids.get(i));
        }
        redis.delete(REDIS_KEY);
    }

    /** k6 스크립트와 같은 규칙: 계좌 순번 i 의 메뉴. 인기 메뉴는 모두 첫 메뉴(아메리카노). */
    private long menuOf(Scenario s, int i) {
        return s.mode.equals("hot-menu") ? menus.getFirst() : menus.get(i % menus.size());
    }

    private void deleteExperimentUsers() {
        String in = "SELECT id FROM users WHERE name LIKE '" + NAME_PREFIX + "%'";
        jdbc.update("DELETE FROM point_histories WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (" + in + "))");
        jdbc.update("DELETE FROM orders WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM cart_items WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM points WHERE user_id IN (" + in + ")");
        jdbc.update("DELETE FROM users WHERE name LIKE '" + NAME_PREFIX + "%'");
        redis.delete(REDIS_KEY);
    }

    // ---- 검증 ----

    /** 한 회차가 끝난 뒤 DB · Redis 를 대조한다. 문제를 문자열로 모아 돌려준다(측정을 멈추지 않으려고 바로 단언하지 않는다). */
    private List<String> verify(Scenario s, List<Long> ids, K6 k6, String label) {
        List<String> problems = new ArrayList<>();
        if (k6.unexpected > 0) {
            problems.add(label + ": 예상 밖 응답 " + k6.unexpected + "건(콘솔의 k6 로그 참고)");
        }
        if (k6.cartConflict > 0) {
            problems.add(label + ": 발견 - 각자 계좌인데 장바구니 충돌(409) " + k6.cartConflict + "건");
        }
        for (int i = 0; i < ids.size(); i++) {
            long u = ids.get(i);
            String who = label + " 사용자 " + u;
            long balance = jdbc.queryForObject("SELECT balance FROM points WHERE user_id = ?", Long.class, u);
            long charged = sumHistory(u, "CHARGE");
            long used = sumHistory(u, "USE");
            long refunded = sumHistory(u, "REFUND");
            long expected = startBalance.get(u) + charged - used + refunded;
            check(problems, balance == expected, who + ": 잔액 " + balance + " ≠ 시작+충전−사용+환불 " + expected);
            check(problems, balance >= 0, who + ": 잔액 음수 " + balance);
            List<Long> last = jdbc.queryForList(
                    "SELECT balance_after FROM point_histories WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, u);
            check(problems, last.isEmpty() || last.getFirst() == balance, who + ": 마지막 내역 잔액 " + last + " ≠ " + balance);
            long orderSum = count("SELECT COALESCE(SUM(total_price), 0) FROM orders WHERE user_id = ?", u);
            check(problems, orderSum == used, who + ": 사용 내역 합 " + used + " ≠ 주문 금액 합 " + orderSum);
            long orders = count("SELECT COUNT(*) FROM orders WHERE user_id = ?", u);
            long useCount = count("SELECT COUNT(*) FROM point_histories WHERE user_id = ? AND type = 'USE'", u);
            check(problems, orders == useCount, who + ": 주문 " + orders + "건 ≠ 사용 내역 " + useCount + "건");
            long refundedOrders = count("SELECT COUNT(*) FROM orders WHERE user_id = ? AND status = 'REFUNDED'", u);
            long refundCount = count("SELECT COUNT(*) FROM point_histories WHERE user_id = ? AND type = 'REFUND'", u);
            check(problems, refundedOrders == refundCount, who + ": 환불 주문 " + refundedOrders + "건 ≠ 환불 내역 " + refundCount);
            long cart = count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", u);
            if (s.mode.equals("floor")) {
                long price = prices.get(menuOf(s, i));
                check(problems, orders == FLOOR_ORDERS, who + ": 잔액 바닥인데 성공 주문 " + orders + "건(기대 " + FLOOR_ORDERS + ")");
                check(problems, balance == price - 1, who + ": 잔액 바닥 남은 잔액 " + balance + "(기대 " + (price - 1) + ")");
                check(problems, cart == 1, who + ": 잔액 부족 뒤 남은 장바구니 " + cart + "행(기대 1)");
            } else {
                // 마지막 주문 요청이 서버에 못 닿았으면(k6 연결 실패) 담긴 1행이 남을 수 있다
                check(problems, cart == 0 || (k6.clientFail > 0 && cart == 1), who + ": 장바구니가 " + cart + "행 남음");
            }
        }
        String in = in(ids);
        check(problems, count("SELECT COUNT(*) FROM (SELECT order_id FROM point_histories WHERE user_id IN (" + in
                + ") AND type = 'REFUND' GROUP BY order_id HAVING COUNT(*) > 1) t") == 0, label + ": 이중 환불");
        long dbOrders = count("SELECT COUNT(*) FROM orders WHERE user_id IN (" + in + ")");
        check(problems, dbOrders == k6.orderOk, label + ": k6 성공 주문 " + k6.orderOk + " ≠ DB 주문 " + dbOrders);
        long dbCharges = count("SELECT COUNT(*) FROM point_histories WHERE user_id IN (" + in + ") AND type = 'CHARGE'");
        check(problems, dbCharges == k6.chargeOk, label + ": k6 충전 " + k6.chargeOk + " ≠ DB 충전 내역 " + dbCharges);
        long dbRefunds = count("SELECT COUNT(*) FROM point_histories WHERE user_id IN (" + in + ") AND type = 'REFUND'");
        check(problems, dbRefunds == k6.refundOk, label + ": k6 환불 " + k6.refundOk + " ≠ DB 환불 내역 " + dbRefunds);
        if (s.mode.equals("floor")) {
            check(problems, k6.orderRejected > 0, label + ": 잔액 부족 거절이 한 건도 없음");
        }
        if (s.mode.equals("multi-device")) {
            check(problems, k6.chargeOk > 0 && k6.refundOk > 0, label + ": 충전 · 환불이 섞이지 않음");
        }
        // 인기 메뉴: 커밋 후 처리는 응답 전에 같은 요청 스레드에서 끝나므로 k6 가 끝나면 다 반영돼 있어야 한다
        Map<Long, Long> db = new HashMap<>();
        jdbc.query("SELECT oi.menu_id, SUM(oi.quantity) q FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.user_id IN (" + in + ") AND o.status = 'PAID' GROUP BY oi.menu_id HAVING SUM(oi.quantity) > 0",
                rs -> {
                    db.put(rs.getLong("menu_id"), rs.getLong("q"));
                });
        Map<Long, Long> cached = new HashMap<>();
        for (TypedTuple<String> t : Objects.requireNonNullElse(redis.opsForZSet().rangeWithScores(REDIS_KEY, 0, -1),
                Set.<TypedTuple<String>>of())) {
            if (t.getScore() != null && t.getScore() > 0) {
                cached.put(Long.parseLong(t.getValue()), t.getScore().longValue());
            }
        }
        check(problems, cached.equals(db), label + ": Redis 인기 메뉴 " + cached + " ≠ 결제 상태 주문 수량 " + db);
        return problems;
    }

    private static void check(List<String> problems, boolean ok, String message) {
        if (!ok) {
            problems.add(message);
        }
    }

    private long sumHistory(long userId, String type) {
        return count("SELECT COALESCE(SUM(amount), 0) FROM point_histories WHERE user_id = ? AND type = ?", userId, type);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** SQL IN 목록. DB 가 만든 사용자 id(long)만 받으므로 문자열로 이어 붙여도 안전하다. */
    private static String in(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }

    // ---- 측정 ----

    /**
     * k6 를 duration 동안 돌려 요약 줄을 읽는다. outJson 이 null 이면 요약 JSON 을 남기지 않는다(예열). 멈추면 K6_TIMEOUT_MINUTES 뒤 끊는다.
     * 종료 코드 99(checks 임계값 깨짐 = 예상 밖 응답)는 측정을 끝까지 하려고 받아들이고, 건수는 verify 가 문제로 남긴다.
     */
    private K6 runK6(Scenario s, List<Long> ids, String duration, String outJson) throws IOException, InterruptedException {
        String bin = Objects.requireNonNullElse(System.getenv("K6_BIN"), "k6");
        Path log = Files.createTempFile("k6-", ".log");
        List<String> command = new ArrayList<>(List.of(bin, "run", "--quiet", "-e", "BASE_URL=http://localhost:" + port,
                "-e", "MODE=" + s.mode, "-e", "USER_IDS=" + in(ids), "-e", "MENU_IDS=" + in(menus),
                "-e", "DURATION=" + duration));
        if (outJson != null) {
            Files.deleteIfExists(Path.of(outJson)); // 이전 실행 파일이 남아 있으면 아래 존재 단언이 이번 실행을 증명하지 못한다
            command.addAll(List.of("-e", "OUT=" + outJson));
        }
        command.add("k6/order-load.js");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(K6_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if (finished) { // 강제 종료 직후엔 Windows 가 파일을 아직 잡고 있을 수 있어, 남겨 두고 실패 이유를 먼저 보인다
            Files.deleteIfExists(log);
        }
        assertThat(finished).as("k6 가 " + K6_TIMEOUT_MINUTES + "분 안에 끝나야 한다\n" + output).isTrue();
        assertThat(process.exitValue()).as("k6 종료 코드\n" + output).isIn(0, K6_THRESHOLD_FAILED);
        Matcher m = K6_LINE.matcher(output);
        assertThat(m.find()).as("k6 요약 줄\n" + output).isTrue();
        if (outJson != null) {
            assertThat(Path.of(outJson)).as("k6 요약 JSON\n" + output).exists();
        }
        K6 k6 = new K6(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5)), Long.parseLong(m.group(6)),
                Long.parseLong(m.group(7)), Long.parseLong(m.group(8)), Long.parseLong(m.group(9)),
                Long.parseLong(m.group(10)), Long.parseLong(m.group(11)), Long.parseLong(m.group(12)),
                Long.parseLong(m.group(13)));
        if (process.exitValue() == K6_THRESHOLD_FAILED) {
            assertThat(k6.unexpected).as("종료 코드 99 인데 예상 밖 응답이 0건\n" + output).isPositive();
            System.out.println(output); // 예상 밖 응답의 내용(k6 console.error)을 남긴다
        }
        return k6;
    }

    private static double median(List<K6> runs, ToDoubleFunction<K6> metric) {
        double[] sorted = runs.stream().mapToDouble(metric).sorted().toArray();
        return sorted[sorted.length / 2];
    }
}
