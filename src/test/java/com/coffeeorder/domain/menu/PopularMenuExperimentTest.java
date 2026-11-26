package com.coffeeorder.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.menu.service.PopularMenuDbService;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
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
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 인기 메뉴 Redis vs DB 집계 실험(노션 성능·동작 실험 EXP-4): 같은 판매 데이터로 두 조회 API 를 k6(가상 사용자 50명, 30초)로 잰다.
 * 데이터는 현실형으로 하루 주문량을 같게 두고 쌓인 기간만 바꾼다(11/26 경화님 결정):
 *   1만 건 = 최근 7일치(서비스 초기) / 10만 건 = 70일치(최근 7일은 똑같이 1만 건, 나머지는 7일보다 오래된 주문).
 * 그래서 두 경우 인기 메뉴 결과는 같고, DB 방식이 훑어야 하는 주문만 10배가 된다. Redis 방식은 날짜 키 7개 + 메뉴 이름을 위한
 * 메뉴 PK 조회 1번이라(PopularMenuService.getTop3) 주문 수와 상관없다. Redis 경로는 두 경우 하는 일이 같아 측정 환경의 대조군으로 쓴다.
 * 주문 1건에 메뉴 2종, 10%는 환불. 시계를 2096-06-30(서울)로 고정해 다른 테스트 데이터와 겹치지 않게 하고, 끝나면 지운다.
 * 테스트 설정이라 coffee_order_test 스키마와 Redis 1번 DB 를 쓴다(개발 DB 에 쓰지 않음). SQL 로그는 꺼서 측정에 섞이지 않게 한다.
 * 평소 테스트에서는 건너뛴다. k6 가 PATH 에 있어야 한다(없으면 K6_BIN 에 실행 파일 경로).
 * 실행: EXPERIMENT=true ./gradlew test --tests '*PopularMenuExperimentTest' --rerun-tasks -i
 * 결과: build/experiment/popular-menu-experiment.txt + 콘솔(-i), k6 요약 JSON 은 build/experiment/exp4-*.json
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"experiment.popular-db.enabled=true", "spring.jpa.show-sql=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EXPERIMENT", matches = "true")
class PopularMenuExperimentTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2096, 6, 30);
    private static final int RECENT_DAYS = 7;
    private static final int RUNS = 3; // 측정 반복(중앙값). 홀수
    private static final String K6_WARMUP = "10s";
    private static final int CONTROL_DRIFT_PERCENT = 15; // 대조군 변동이 이보다 크면 그 실행의 절대 수치는 비교에 쓰지 않는다
    private static final int QUERY_WARMUP = 3;
    private static final int QUERY_RUNS = 20;
    // EXPLAIN ANALYZE 트리에서 orders 를 읽는 단계(전체 훑기)의 실제 행 수
    private static final Pattern ORDERS_SCAN = Pattern.compile("scan on o .*?actual time=\\S+ rows=(\\d+)");
    private static final String REDIS_PATH = "/api/menus/popular";
    private static final String DB_PATH = "/api/menus/popular/db";
    private static final Pattern K6_LINE =
            Pattern.compile("TPS=([\\d.]+) avg=([\\d.]+)ms p95=([\\d.]+)ms failed=(\\d+)");
    private static final int RECENT_ORDERS = 10_000; // 최근 7일 주문 수(두 경우 같음)
    // 주문 순번 n 은 total_price 칸에 실어 둔다(집계에 안 쓰는 칸). id 는 실행마다 달라서 n 으로 메뉴를 정해야 두 경우가 같아진다
    private static final String N = "(o.total_price - 1000000)";
    // 주문의 첫 메뉴를 5:4:3:2:1 비율로 나눈다(메뉴 5종). 두 번째 메뉴는 첫 메뉴와 다른 것으로
    private static final String FIRST_MENU = "CASE WHEN " + N + " % 15 < 5 THEN 1 WHEN " + N + " % 15 < 9 THEN 2 "
            + "WHEN " + N + " % 15 < 12 THEN 3 WHEN " + N + " % 15 < 14 THEN 4 ELSE 5 END";
    private static final String SECOND_MENU = "MOD(" + FIRST_MENU + " + " + N + " % 4, 5) + 1";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2096-06-30T03:00:00Z"), SEOUL); // 서울 12:00
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired PopularMenuRepository popularMenuRepository;
    @Autowired UserRepository userRepository;
    @Autowired DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private long userId;
    private List<Long> menus;

    @BeforeAll
    void 준비() throws IOException {
        // 이전 실행이 강제 종료돼 남긴 실험 주문(2096년)과 사용자부터 지운다. DB 집계는 사용자로 거르지 않아서 남으면 결과가 틀어진다
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE created_at >= '2096-01-01' "
                + "AND created_at < '2097-01-01')");
        jdbc.update("DELETE FROM orders WHERE created_at >= '2096-01-01' AND created_at < '2097-01-01'");
        jdbc.update("DELETE FROM users WHERE name = 'EXP4'");
        userId = userRepository.save(new User("EXP4")).getId();
        menus = List.of(menuId("아메리카노"), menuId("폭신폭신 구름과자 라떼"), menuId("졸린 곰돌이 우유"),
                menuId("번개맞은 바닐라"), menuId("달빛 두리안 프라푸치노"));
        cleanUp();
        Files.createDirectories(Path.of("build", "experiment")); // k6 는 요약 JSON 의 폴더를 만들어 주지 않는다
    }

    @AfterAll
    void 정리() {
        cleanUp();
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 인기_메뉴_Redis_vs_DB_집계_k6() throws Exception {
        List<String> report = new ArrayList<>();
        report.add("[EXP-4] k6 가상 사용자 50명 30초 × " + RUNS + "회(중앙값, 측정마다 버리는 k6 예열 " + K6_WARMUP
                + "), 조회 API 만. 하루 약 1,430건 · 주문 1건에 메뉴 2종 · 10% 환불");
        report.add("  조건: DB 연결 풀 최대 " + ((HikariDataSource) dataSource).getMaximumPoolSize()
                + "개(기본값), 서버는 테스트 JVM 안, MySQL·Redis(Docker)·k6 모두 같은 PC");
        String firstBody = null;
        List<Double> redisTps = new ArrayList<>();
        for (int[] scenario : new int[][] {{10_000, 7}, {100_000, 70}}) {
            int total = scenario[0];
            int days = scenario[1];
            insertOrders(total, days);
            fillRedis();
            long recent = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ? AND created_at >= ?",
                    Long.class, userId, TODAY.minusDays(RECENT_DAYS - 1).atStartOfDay());
            assertThat(recent).as("최근 7일 주문은 두 경우 모두 1만 건").isEqualTo(RECENT_ORDERS);

            // 부하 전에 두 API 가 같은 답을 내는지 확인한다(다르면 속도 비교가 무의미)
            String redisBody = get(REDIS_PATH);
            String dbBody = get(DB_PATH);
            assertThat(dbBody).isEqualTo(redisBody).contains("\"rank\":3");
            if (firstBody == null) {
                firstBody = redisBody;
            } else {
                assertThat(redisBody).as("쌓인 기간만 다르므로 인기 메뉴 결과는 같다").isEqualTo(firstBody);
            }

            String label = total / 1000 + "k";
            report.add(String.format("주문 %,d건(%d일치, 최근 7일 1만 건) | DB EXPLAIN %s", total, days, explain()));
            // 부하 없이 DB 집계 쿼리 1건만: 동시 부하 중에는 연결 줄서기·CPU 경합에 묻히는 쿼리 자체 비용을 따로 본다
            report.add(String.format("  DB 쿼리 1건(부하 없음) | 평균 %.2fms (예열 %d회 뒤 %d회) | 실제로 읽은 주문 %,d행",
                    queryMs(), QUERY_WARMUP, QUERY_RUNS, ordersRead()));
            for (String[] target : new String[][] {{"Redis", REDIS_PATH}, {"DB 집계", DB_PATH}}) {
                // 동시 사용자 50명 상태(스레드·연결 풀·JIT)를 데우고 버린다. 한 명이 순서대로 부르는 예열로는 부족했다(리뷰)
                runK6(target[1], K6_WARMUP, null);
                List<K6> runs = new ArrayList<>();
                for (int r = 1; r <= RUNS; r++) {
                    K6 k6 = runK6(target[1], "30s", "build/experiment/exp4-"
                            + (target[1].equals(DB_PATH) ? "db" : "redis") + "-" + label + "-" + r + ".json");
                    assertThat(k6.failed).as(target[0] + " 실패한 요청 수").isZero();
                    runs.add(k6);
                }
                double tps = median(runs, K6::tps);
                if (target[1].equals(REDIS_PATH)) {
                    redisTps.add(tps);
                }
                report.add(String.format("  %-7s | TPS %,8.1f | 평균 %8.2fms | p95 %8.2fms | 실패 0건 | TPS 3회 %s",
                        target[0], tps, median(runs, K6::avgMs), median(runs, K6::p95Ms),
                        runs.stream().map(k -> String.format("%.1f", k.tps)).toList()));
            }
            cleanUp();
        }
        // Redis 경로는 두 경우 하는 일이 같다(대조군). 이것이 크게 움직였으면 PC 상태가 흔들린 것이라 절대 수치 비교를 믿지 않는다
        double drift = Math.abs(redisTps.get(1) - redisTps.get(0)) / redisTps.get(0) * 100;
        report.add(String.format("대조군(Redis) 변동 %.1f%% → %s", drift, drift <= CONTROL_DRIFT_PERCENT
                ? "이 실행 안의 비교(1만↔10만, Redis↔DB 배수)에만 유효(±" + CONTROL_DRIFT_PERCENT
                        + "% 이내). 절대 수치는 실행마다 달라 인용하지 않는다"
                : "비교 불가: 측정 환경이 흔들림. 이 실행의 수치는 인용하지 말고 다시 실행"));
        report.add("응답: " + firstBody);
        report.forEach(System.out::println);
        Files.write(Path.of("build", "experiment", "popular-menu-experiment.txt"), report);
    }

    // ---- 데이터 ----

    /**
     * 주문 total 건을 넣는다. 순번 n < 1만은 최근 7일(오늘 포함)에 고르게, 나머지는 그 이전 days-7 일에 고르게 나눈다.
     * 그래서 최근 7일 주문(날짜·시각·상태·메뉴)은 두 경우가 똑같다. 0~9 표를 5번 곱해 0~99,999 를 만든다.
     */
    private void insertOrders(int total, int days) {
        String digits = "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.update("INSERT INTO orders (user_id, total_price, status, created_at) "
                + "SELECT ?, 1000000 + n, IF(n % 10 = 0, 'REFUNDED', 'PAID'), TIMESTAMP(?) - INTERVAL "
                + "(IF(n < ?, n % 7, 7 + (n - ?) % GREATEST(? - 7, 1))) DAY + INTERVAL ((n * 37) % 86400) SECOND FROM ("
                + "SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 n FROM "
                + digits + " a, " + digits + " b, " + digits + " c, " + digits + " e, " + digits + " f) seq "
                + "WHERE n < ? ORDER BY n", userId, Date.valueOf(TODAY), RECENT_ORDERS, RECENT_ORDERS, days, total);
        for (String[] item : new String[][] {{FIRST_MENU, N + " % 3 + 1"}, {SECOND_MENU, N + " % 2 + 1"}}) {
            jdbc.update("INSERT INTO order_items (order_id, menu_id, menu_name, unit_price, quantity, line_price) "
                    + "SELECT x.id, m.id, m.name, m.price, x.q, m.price * x.q FROM ("
                    + "SELECT o.id, CAST(ELT(" + item[0] + ", ?, ?, ?, ?, ?) AS UNSIGNED) menu_id, " + item[1] + " q "
                    + "FROM orders o WHERE o.user_id = ?) x JOIN menus m ON m.id = x.menu_id",
                    menus.get(0), menus.get(1), menus.get(2), menus.get(3), menus.get(4), userId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.user_id = ?", Long.class, userId)).isEqualTo(total * 2L);
        jdbc.execute("ANALYZE TABLE orders, order_items");
    }

    /** 실제 주문 흐름이 Redis 에 쌓는 것과 같게: 결제 완료 주문의 날짜별·메뉴별 수량을 그 날짜 키에 더한다(최근 7일만). */
    private void fillRedis() {
        Map<LocalDate, Map<Long, Integer>> byDay = new HashMap<>();
        jdbc.query("SELECT DATE(o.created_at) d, oi.menu_id, SUM(oi.quantity) q FROM orders o "
                + "JOIN order_items oi ON oi.order_id = o.id WHERE o.user_id = ? AND o.status = 'PAID' "
                + "AND o.created_at >= ? GROUP BY d, oi.menu_id", rs -> {
                    byDay.computeIfAbsent(rs.getDate("d").toLocalDate(), d -> new HashMap<>())
                            .put(rs.getLong("menu_id"), rs.getInt("q"));
                }, userId, TODAY.minusDays(RECENT_DAYS - 1).atStartOfDay());
        assertThat(byDay).hasSize(RECENT_DAYS);
        byDay.forEach(popularMenuRepository::increase);
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
        for (int i = 0; i < RECENT_DAYS; i++) {
            redisTemplate.delete("popular:menu:" + TODAY.minusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE));
        }
    }

    // ---- 측정 ----

    private record K6(double tps, double avgMs, double p95Ms, long failed) {}

    /** k6 를 duration 동안 돌려 요약 줄을 읽는다. outJson 이 null 이면 요약 JSON 을 남기지 않는다(예열). 멈추면 2분 뒤 끊는다. */
    private K6 runK6(String path, String duration, String outJson) throws IOException, InterruptedException {
        String bin = Objects.requireNonNullElse(System.getenv("K6_BIN"), "k6");
        Path log = Files.createTempFile("k6-", ".log");
        List<String> command = new ArrayList<>(List.of(bin, "run", "--quiet", "-e", "BASE_URL=http://localhost:" + port,
                "-e", "TARGET_PATH=" + path, "-e", "DURATION=" + duration));
        if (outJson != null) {
            Files.deleteIfExists(Path.of(outJson)); // 이전 실행 파일이 남아 있으면 아래 존재 단언이 이번 실행을 증명하지 못한다
            command.addAll(List.of("-e", "OUT=" + outJson));
        }
        command.add("k6/popular-menu.js");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if (finished) { // 강제 종료 직후엔 Windows 가 파일을 아직 잡고 있을 수 있어, 남겨 두고 실패 이유를 먼저 보인다
            Files.deleteIfExists(log);
        }
        assertThat(finished).as("k6 가 2분 안에 끝나야 한다\n" + output).isTrue();
        assertThat(process.exitValue()).as("k6 종료 코드\n" + output).isZero();
        Matcher m = K6_LINE.matcher(output);
        assertThat(m.find()).as("k6 요약 줄\n" + output).isTrue();
        if (outJson != null) {
            assertThat(Path.of(outJson)).as("k6 요약 JSON\n" + output).exists();
        }
        return new K6(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                Long.parseLong(m.group(4)));
    }

    private static double median(List<K6> runs, ToDoubleFunction<K6> metric) {
        double[] sorted = runs.stream().mapToDouble(metric).sorted().toArray();
        return sorted[sorted.length / 2];
    }

    /** DB 집계 쿼리의 실행 계획(표마다 type·key·예상 rows). orders 에 날짜·상태 인덱스가 없어 전체를 읽는지 본다. */
    private String explain() {
        LocalDate from = TODAY.minusDays(RECENT_DAYS - 1);
        List<String> rows = jdbc.query("EXPLAIN " + PopularMenuDbService.TOP3_SQL,
                (rs, i) -> rs.getString("table") + ":" + rs.getString("type") + "/" + rs.getString("key") + "/rows="
                        + rs.getString("rows"), "PAID", from.atStartOfDay(), TODAY.plusDays(1).atStartOfDay());
        return String.join(", ", rows);
    }

    private Object[] queryArgs() {
        return new Object[] {"PAID", TODAY.minusDays(RECENT_DAYS - 1).atStartOfDay(), TODAY.plusDays(1).atStartOfDay()};
    }

    /** DB 집계 쿼리를 혼자(동시 부하 없이) 돌린 평균 시간. */
    private double queryMs() {
        for (int i = 0; i < QUERY_WARMUP; i++) {
            jdbc.queryForList(PopularMenuDbService.TOP3_SQL, queryArgs());
        }
        long start = System.nanoTime();
        for (int i = 0; i < QUERY_RUNS; i++) {
            jdbc.queryForList(PopularMenuDbService.TOP3_SQL, queryArgs());
        }
        return (System.nanoTime() - start) / 1_000_000.0 / QUERY_RUNS;
    }

    /** EXPLAIN ANALYZE 로 실제 실행해, orders 를 훑은 단계가 읽은 행 수. */
    private long ordersRead() {
        String tree = String.join("\n", jdbc.queryForList("EXPLAIN ANALYZE " + PopularMenuDbService.TOP3_SQL,
                String.class, queryArgs()));
        Matcher m = ORDERS_SCAN.matcher(tree);
        assertThat(m.find()).as("orders 훑기 단계\n" + tree).isTrue();
        return Long.parseLong(m.group(1));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(res.statusCode()).as(path).isEqualTo(200);
        return res.body();
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }
}
