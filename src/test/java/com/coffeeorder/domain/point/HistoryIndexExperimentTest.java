package com.coffeeorder.domain.point;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 포인트 내역 조회 인덱스 실험(노션 성능·동작 실험 EXP-5): 내역 10만 건에서 실제 내역 조회 쿼리가 인덱스에 따라 얼마나 달라지는지 잰다.
 * 실제 쿼리(PointHistoryRepository)는 "user_id 로 거르고 id 내림차순으로 size+1 건"이다(다음 페이지 여부용 +1, PointService.getHistories).
 * created_at 이 아니라 id 로 정렬한다.
 * 실제 point_histories 는 FK 때문에 user_id 인덱스가 이미 있어 "인덱스 없음"을 만들 수 없다 → FK 없는 실험용 표(exp_index_histories)에서
 * 인덱스만 바꿔 같은 쿼리를 돌린다: 없음 / (user_id) = 지금 실제 표와 같은 상태 / (user_id, created_at) = 처음 계획한 인덱스 / (user_id, id).
 * 평소 테스트에서는 건너뛴다. 실행: EXPERIMENT=true ./gradlew test --tests '*HistoryIndexExperimentTest' -i
 * 결과: build/experiment/history-index-experiment.txt + 콘솔(-i 를 붙여야 보인다)
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "EXPERIMENT", matches = "true")
class HistoryIndexExperimentTest {

    private static final int USERS = 1_000; // 1,000명 × 100건 = 10만 건, id 는 사용자끼리 섞여 쌓인다(실제처럼)
    private static final long TARGET_USER = 500;
    private static final long EMPTY_USER = USERS + 1; // 내역이 하나도 없는 사용자(새로 가입한 사람)
    private static final Pattern ACTUAL_ROWS = Pattern.compile("actual time=\\S+ rows=(\\d+)");
    private static final int SIZE = 10; // 내역 조회 기본 size
    private static final int FETCH = SIZE + 1; // 실제 쿼리가 읽는 건수(PointService.getHistories 의 Limit.of(size + 1))
    private static final int WARMUP = 3;
    private static final int RUNS = 10;
    private static final String FIRST_PAGE =
            "SELECT * FROM exp_index_histories WHERE user_id = ? ORDER BY id DESC LIMIT " + FETCH;
    private static final String NEXT_PAGE =
            "SELECT * FROM exp_index_histories WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT " + FETCH;

    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void 실험용_표_10만_건() {
        jdbc.execute("DROP TABLE IF EXISTS exp_index_histories");
        // point_histories 와 같은 컬럼, FK·보조 인덱스 없이 PK 만
        jdbc.execute("CREATE TABLE exp_index_histories (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "order_id BIGINT NULL, type VARCHAR(10) NOT NULL, amount BIGINT NOT NULL, balance_after BIGINT NOT NULL, "
                + "created_at DATETIME(6) NOT NULL)");
        // 0~9 를 5번 곱해 0~99,999 를 만든다. n 순서대로 넣어 id 가 사용자끼리 섞인다
        String digits = "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        jdbc.execute("INSERT INTO exp_index_histories (user_id, type, amount, balance_after, created_at) "
                + "SELECT n % " + USERS + " + 1, 'CHARGE', 1000, 1000, TIMESTAMP('2026-11-01') + INTERVAL n SECOND FROM ("
                + "SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 n FROM "
                + digits + " a, " + digits + " b, " + digits + " c, " + digits + " e, " + digits + " f) seq ORDER BY n");
    }

    @AfterAll
    void 실험용_표_삭제() {
        jdbc.execute("DROP TABLE IF EXISTS exp_index_histories");
    }

    @Test
    void 인덱스_4가지_EXPLAIN_과_조회_시간_비교() throws IOException {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exp_index_histories", Long.class)).isEqualTo(100_000L);
        long cursor = jdbc.queryForObject( // 두 번째 페이지 커서 = 첫 페이지에 보여 줄 size 건 중 마지막 id
                "SELECT MIN(id) FROM (SELECT id FROM exp_index_histories WHERE user_id = ? ORDER BY id DESC LIMIT " + SIZE
                        + ") p", Long.class, TARGET_USER);

        List<String> report = new ArrayList<>();
        report.add("[EXP-5] 내역 10만 건, 사용자 " + USERS + "명 × 100건, 조회 대상 1명, size " + SIZE + "(실제로 읽는 건수 "
                + FETCH + "), 예열 " + WARMUP + "회 뒤 " + RUNS + "회 평균");
        Result none = measure("없음", null, cursor, report);
        Result userId = measure("(user_id)", "CREATE INDEX idx_user ON exp_index_histories (user_id)", cursor, report);
        jdbc.execute("DROP INDEX idx_user ON exp_index_histories");
        Result userCreated = measure("(user_id, created_at)",
                "CREATE INDEX idx_user_created ON exp_index_histories (user_id, created_at)", cursor, report);
        jdbc.execute("DROP INDEX idx_user_created ON exp_index_histories");
        // (user_id) 도 안쪽에 id 를 품지만, 커서 페이지에서 옵티마이저가 다른 길(index_merge + 정렬)을 골라 id 를 직접 적어 비교한다
        Result userIdId = measure("(user_id, id)", "CREATE INDEX idx_user_id ON exp_index_histories (user_id, id)", cursor, report);

        report.forEach(System.out::println);
        Path out = Path.of("build", "experiment", "history-index-experiment.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);

        // 인덱스가 바뀌어도 결과(같은 건들)는 같아야 비교가 의미 있다
        assertThat(userId.firstIds).isEqualTo(none.firstIds).isEqualTo(userCreated.firstIds).isEqualTo(userIdId.firstIds)
                .hasSize(FETCH);
        // EXPLAIN 의 rows 는 예상치라 판정은 EXPLAIN ANALYZE 의 실제 행 수로 한다
        assertThat(userId.firstRead).as("(user_id) 인덱스면 첫 페이지는 딱 size+1 건만 읽는다").isEqualTo(FETCH);
        assertThat(String.valueOf(userId.first.get("Extra"))).doesNotContain("filesort");
        assertThat(none.firstRead).as("인덱스가 없으면 최신 행부터 거슬러 읽으며 그 사용자 것을 찾는다").isGreaterThan(1_000);
        assertThat(none.emptyRead).as("내역이 없는 사용자면 끝까지 다 읽는다").isGreaterThanOrEqualTo(100_000);
        assertThat(userId.emptyRead).isZero();
    }

    private record Result(Map<String, Object> first, long firstRead, long emptyRead, List<Long> firstIds) {}

    private Result measure(String label, String createIndex, long cursor, List<String> report) {
        if (createIndex != null) {
            jdbc.execute(createIndex);
        }
        jdbc.execute("ANALYZE TABLE exp_index_histories"); // 인덱스 없음도 같은 조건으로 통계를 새로 만든다
        Map<String, Object> first = jdbc.queryForMap("EXPLAIN " + FIRST_PAGE, TARGET_USER);
        Map<String, Object> next = jdbc.queryForMap("EXPLAIN " + NEXT_PAGE, TARGET_USER, cursor);
        Map<String, Object> empty = jdbc.queryForMap("EXPLAIN " + FIRST_PAGE, EMPTY_USER);
        long firstRead = rowsRead(FIRST_PAGE, TARGET_USER);
        long nextRead = rowsRead(NEXT_PAGE, TARGET_USER, cursor);
        long emptyRead = rowsRead(FIRST_PAGE, EMPTY_USER);
        report.add(line(label, "첫 페이지(100건 사용자)", first, firstRead, averageMs(FIRST_PAGE, TARGET_USER)));
        report.add(line("", "다음 페이지(커서)", next, nextRead, averageMs(NEXT_PAGE, TARGET_USER, cursor)));
        report.add(line("", "첫 페이지(내역 0건 사용자)", empty, emptyRead, averageMs(FIRST_PAGE, EMPTY_USER)));
        List<Long> ids = jdbc.queryForList(FIRST_PAGE.replace("SELECT *", "SELECT id"), Long.class, TARGET_USER);
        return new Result(first, firstRead, emptyRead, ids);
    }

    private static String line(String label, String query, Map<String, Object> explain, long read, double ms) {
        String extra = String.valueOf(explain.get("Extra"));
        // 인덱스 조건 푸시다운(ICP)이 걸리면 엔진 안에서 걸러진 항목은 행 수에 안 잡힌다 → 실제로 훑은 항목이 더 많을 수 있다
        // (조건이 인덱스 범위에 들어가는 (user_id, id) 커서 페이지처럼 걸러지는 게 없으면 같다)
        String icp = extra.contains("index condition") ? " (ICP: 훑은 항목이 더 많을 수 있음)" : "";
        return String.format("  %-22s | %-16s | type=%s key=%s 예상rows=%s Extra=%s | 엔진이 돌려준 행 %,d%s | %.2fms", label,
                query, explain.get("type"), explain.get("key"), explain.get("rows"), extra, read, icp, ms);
    }

    /**
     * EXPLAIN ANALYZE 로 실제 실행해, 단계별 실제 행 수 가운데 가장 큰 값을 돌려준다(= 저장소 엔진이 돌려준 행 수).
     * 단계별 합이 아니라 최댓값이라 "몇 행을 읽었나" 비교용이지 비용 비교용은 아니다.
     */
    private long rowsRead(String sql, Object... args) {
        String tree = String.join("\n", jdbc.queryForList("EXPLAIN ANALYZE " + sql, String.class, args));
        Matcher m = ACTUAL_ROWS.matcher(tree);
        long max = 0;
        while (m.find()) {
            max = Math.max(max, Long.parseLong(m.group(1)));
        }
        return max;
    }

    private double averageMs(String sql, Object... args) {
        for (int i = 0; i < WARMUP; i++) {
            jdbc.queryForList(sql, args);
        }
        long start = System.nanoTime();
        for (int i = 0; i < RUNS; i++) {
            jdbc.queryForList(sql, args);
        }
        return (System.nanoTime() - start) / 1_000_000.0 / RUNS;
    }
}
