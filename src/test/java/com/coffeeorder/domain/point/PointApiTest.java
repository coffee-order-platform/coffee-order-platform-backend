package com.coffeeorder.domain.point;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.repository.OrderRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.domain.point.service.PointService;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 테스트 케이스 C(충전)·B(잔액)·H(내역)를 실제 MySQL 에서 확인한다.
 * 테스트마다 자기 사용자를 만들고, 끝나면 롤백되어 시드 데이터는 그대로 남는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PointApiTest {

    private static final String INVALID_CHARGE_AMOUNT =
            "{\"status\":400,\"code\":\"INVALID_CHARGE_AMOUNT\",\"message\":\"충전 금액이 올바르지 않습니다.\"}";
    private static final String INVALID_REQUEST =
            "{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String USER_NOT_FOUND =
            "{\"status\":404,\"code\":\"USER_NOT_FOUND\",\"message\":\"사용자를 찾을 수 없습니다.\"}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired PointHistoryRepository pointHistoryRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired DataSource dataSource;

    private User user;
    private long missingUserId;

    @BeforeEach
    void 테스트_사용자() {
        user = userRepository.saveAndFlush(new User("포인트테스터"));
        pointRepository.saveAndFlush(new Point(user, 150_000));
        missingUserId = user.getId() + 100_000; // 존재하지 않는 사용자(ID 하드코딩 대신 상대값)
    }

    // ---- 충전 C ----

    @Test
    void C1_최소_금액_충전은_잔액을_올리고_CHARGE_내역을_1건_남긴다() throws Exception {
        MockHttpServletResponse res = charge(user.getId(), "{\"amount\":1000}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8))
                .isEqualTo("{\"status\":200,\"message\":\"포인트 충전 성공\",\"data\":{\"balance\":151000}}");
        assertThat(balanceInDb()).isEqualTo(151_000L);
        assertThat(historyCount()).isEqualTo(1L);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT type, amount, balance_after, order_id FROM point_histories WHERE user_id = ?", user.getId());
        assertThat(row.get("type")).isEqualTo("CHARGE");
        assertThat(((Number) row.get("amount")).longValue()).isEqualTo(1_000L);
        assertThat(((Number) row.get("balance_after")).longValue()).isEqualTo(151_000L);
        assertThat(row.get("order_id")).isNull();
    }

    @Test
    void C2_최대_금액_충전은_성공한다() throws Exception {
        MockHttpServletResponse res = charge(user.getId(), "{\"amount\":500000}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(balanceInDb()).isEqualTo(650_000L);
    }

    @ParameterizedTest
    // 501_000·1_000_000 은 1000원 단위라서 상한 검사만이 막는다(단위 검사에 가려지지 않게)
    @ValueSource(longs = {999, 0, -1000, 500_001, 501_000, 1_000_000, 1_500})
    void C3_C4_C5_범위_밖이거나_1000원_단위가_아니면_INVALID_CHARGE_AMOUNT_이고_잔액과_내역은_그대로다(long amount)
            throws Exception {
        MockHttpServletResponse res = charge(user.getId(), "{\"amount\":" + amount + "}");

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_CHARGE_AMOUNT);
        assertThat(balanceInDb()).isEqualTo(150_000L);
        assertThat(historyCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"amount\":null}", "{\"amount\":\"abc\"}", "{\"amount\":",
            "{\"amount\":1000.5}", "{\"amount\":1000.0}", "{\"amount\":\"5000\"}"})
    void C6_E4_금액_누락_문자_깨진_JSON_소수_따옴표_숫자는_INVALID_REQUEST(String body) throws Exception {
        MockHttpServletResponse res = charge(user.getId(), body);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
        assertThat(balanceInDb()).isEqualTo(150_000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "application/json;q=0", "application/json;q=0, */*", "*/*;q=0",
            "application/json;q=abc", "application/json;Q=abc", "application/json;Q=2"})
    void JSON_응답을_받지_않는_충전_요청은_충전하지_않는다(String accept) throws Exception {
        // produces 가 없으면 충전이 먼저 커밋되고 응답 변환에서 실패해 이중 충전 위험이 생긴다(application/xml).
        // q=0 은 "받지 않겠다"는 뜻이라 JsonAcceptConfig 가 실행 전에 막는다(없으면 충전 후 JSON 으로 응답해 버린다)
        MockHttpServletResponse res = mockMvc.perform(post("/api/users/{id}/points/charge", user.getId())
                        .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ACCEPT, accept)
                        .content("{\"amount\":1000}"))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(406);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":406,\"code\":\"NOT_ACCEPTABLE\",\"message\":\"지원하지 않는 응답 형식입니다.\"}");
        assertThat(balanceInDb()).isEqualTo(150_000L);
        assertThat(historyCount()).isZero();
    }

    @Test
    void E3_JSON이_아닌_본문은_UNSUPPORTED_MEDIA_TYPE() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/users/{id}/points/charge", user.getId())
                        .contentType(MediaType.TEXT_PLAIN).content("1000"))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(415);
        assertThat(balanceInDb()).isEqualTo(150_000L);
    }

    @Test
    void E2_충전_주소에_GET은_405이고_Allow_헤더가_POST다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points/charge", user.getId()))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(405);
        assertThat(res.getHeader("Allow")).contains("POST");
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":405,\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"지원하지 않는 요청 방식입니다.\"}");
    }

    @Test
    void C7_없는_사용자_충전은_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = charge(missingUserId, "{\"amount\":1000}");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    @Test
    void 없는_사용자는_금액이_틀려도_USER_NOT_FOUND가_먼저다() throws Exception {
        // 노션 5-1 순서: 사용자 확인 → 금액 검증
        MockHttpServletResponse res = charge(missingUserId, "{\"amount\":999}");

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    // ---- 잔액 B ----

    @Test
    void B1_잔액_조회() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points", user.getId()))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8))
                .isEqualTo("{\"status\":200,\"message\":\"포인트 잔액 조회 성공\",\"data\":{\"balance\":150000}}");
    }

    @Test
    void B2_없는_사용자_잔액은_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points", missingUserId))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    @Test
    void E5_사용자_ID_자리에_문자면_INVALID_REQUEST() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/abc/points")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
    }

    // ---- 내역 H ----

    @Test
    void H1_내역은_최신순이고_응답_칸이_정해진_순서로_나온다() throws Exception {
        charge(user.getId(), "{\"amount\":1000}");
        charge(user.getId(), "{\"amount\":2000}");

        JsonNode data = histories("").path("data");
        JsonNode first = data.path("content").get(0);

        assertThat(first.path("amount").asLong()).isEqualTo(2_000L);
        assertThat(data.path("content").get(1).path("amount").asLong()).isEqualTo(1_000L);
        assertThat(first.propertyNames()).containsExactly(
                "historyId", "type", "amount", "balanceAfter", "orderId", "createdAt");
        assertThat(first.path("type").asString()).isEqualTo("CHARGE");
        assertThat(first.path("orderId").isNull()).isTrue();
        // createdAt 은 값이 있고 초 단위 ISO 형식(소수점 없음)이어야 한다
        assertThat(first.path("createdAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(LocalDateTime.parse(first.path("createdAt").asString())).isNotNull();
    }

    @Test
    void 충전은_포인트_행을_실제로_잠근다() throws Exception {
        // 다른 연결에서 커밋된 시드 사용자의 행을 NOWAIT 로 잠가 본다. 충전이 FOR UPDATE 를 걸었다면 즉시 실패한다.
        // (테스트 트랜잭션은 롤백되므로 시드 잔액은 바뀌지 않는다. flush 전에 확인해야 UPDATE 잠금과 구분된다)
        Long seedUserId = jdbc.queryForObject(
                "SELECT u.id FROM users u JOIN points p ON p.user_id = u.id ORDER BY u.id LIMIT 1", Long.class);
        charge(seedUserId, "{\"amount\":1000}");

        try (Connection other = dataSource.getConnection();
             PreparedStatement ps = other.prepareStatement(
                     "SELECT id FROM points WHERE user_id = ? FOR SHARE NOWAIT")) {
            // FOR SHARE 는 배타 락(PESSIMISTIC_WRITE)과만 충돌한다 → 공유 락(PESSIMISTIC_READ)으로 바뀌면 실패
            other.setAutoCommit(false);
            ps.setLong(1, seedUserId);
            // 3572 = 행이 잠겨 있어 NOWAIT 가 즉시 실패. 구문 오류(1064) 등으로 우연히 통과하지 않게 코드까지 본다
            assertThatThrownBy(ps::executeQuery)
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(3572));
            other.rollback();
        }
    }

    @Test
    void 충전_서비스에는_트랜잭션이_걸려_있다() throws Exception {
        // 이 테스트 클래스는 자체 트랜잭션 안에서 돌아서, 서비스의 @Transactional 이 빠져도 다른 테스트가 알아채지 못한다
        Transactional tx = PointService.class.getMethod("charge", Long.class, long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    void size_1과_100은_허용된다() throws Exception {
        insertHistories(3);

        assertThat(histories("?size=1").path("data").path("content")).hasSize(1);
        assertThat(histories("?size=100").path("data").path("content")).hasSize(3);
    }

    @Test
    void H2_H5_첫_페이지는_10건이고_nextCursor로_나머지를_가져온다() throws Exception {
        List<Long> ids = insertHistories(15); // 오래된 것부터 id 오름차순

        JsonNode page1 = histories("").path("data");
        assertThat(page1.path("content")).hasSize(10);
        assertThat(page1.path("hasNext").asBoolean()).isTrue();
        assertThat(page1.path("content").get(0).path("historyId").asLong()).isEqualTo(ids.get(14));
        long nextCursor = page1.path("nextCursor").asLong();
        assertThat(nextCursor).isEqualTo(ids.get(5)); // 10번째로 최신인 내역

        JsonNode page2 = histories("?cursor=" + nextCursor).path("data");
        assertThat(page2.path("content")).hasSize(5);
        assertThat(page2.path("hasNext").asBoolean()).isFalse();
        assertThat(page2.path("nextCursor").isNull()).isTrue();
        assertThat(page2.path("content").get(0).path("historyId").asLong()).isEqualTo(ids.get(4));
    }

    @Test
    void size를_주면_그만큼_가져오고_정확히_size건이면_hasNext는_false다() throws Exception {
        insertHistories(3);

        JsonNode exact = histories("?size=3").path("data");
        assertThat(exact.path("content")).hasSize(3);
        assertThat(exact.path("hasNext").asBoolean()).isFalse();

        JsonNode smaller = histories("?size=2").path("data");
        assertThat(smaller.path("content")).hasSize(2);
        assertThat(smaller.path("hasNext").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=101", "?size=-1", "?size=abc", "?cursor=0", "?cursor=-1", "?cursor=abc"})
    void H6_size나_cursor가_범위_밖이면_INVALID_REQUEST(String query) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points/histories" + query, user.getId()))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
    }

    @Test
    void 없는_내역_번호나_다른_사용자의_내역을_cursor로_주면_INVALID_REQUEST() throws Exception {
        User other = userRepository.saveAndFlush(new User("다른사용자"));
        PointHistory othersHistory = pointHistoryRepository.saveAndFlush(
                new PointHistory(other, null, PointHistoryType.CHARGE, 1_000, 1_000));

        for (long cursor : new long[] {othersHistory.getId(), othersHistory.getId() + 100_000}) {
            MockHttpServletResponse res = mockMvc
                    .perform(get("/api/users/{id}/points/histories?cursor=" + cursor, user.getId()))
                    .andReturn().getResponse();
            assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
        }
    }

    @Test
    void H7_내역이_없으면_빈_목록() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points/histories", user.getId()))
                .andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":200,\"message\":\"포인트 내역 조회 성공\","
                        + "\"data\":{\"content\":[],\"nextCursor\":null,\"hasNext\":false}}");
    }

    @Test
    void H3_마지막_내역의_balanceAfter는_현재_잔액과_같다() throws Exception {
        charge(user.getId(), "{\"amount\":1000}");
        charge(user.getId(), "{\"amount\":5000}");

        JsonNode latest = histories("").path("data").path("content").get(0);
        assertThat(latest.path("balanceAfter").asLong()).isEqualTo(balanceInDb());
    }

    @Test
    void 사용_내역은_orderId가_나온다() throws Exception {
        Order order = orderRepository.saveAndFlush(new Order(user, 4_500));
        pointHistoryRepository.saveAndFlush(new PointHistory(user, order, PointHistoryType.USE, 4_500, 145_500));

        JsonNode latest = histories("").path("data").path("content").get(0);
        assertThat(latest.path("type").asString()).isEqualTo("USE");
        assertThat(latest.path("orderId").asLong()).isEqualTo(order.getId());
    }

    @Test
    void H4_없는_사용자_내역은_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/points/histories", missingUserId))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    // ---- helpers ----

    private MockHttpServletResponse charge(long userId, String body) throws Exception {
        return mockMvc.perform(post("/api/users/{id}/points/charge", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private JsonNode histories(String query) throws Exception {
        MockHttpServletResponse res = mockMvc
                .perform(get("/api/users/{id}/points/histories" + query, user.getId()))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return objectMapper.readTree(res.getContentAsString(UTF_8));
    }

    private List<Long> insertHistories(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(pointHistoryRepository.saveAndFlush(
                    new PointHistory(user, null, PointHistoryType.CHARGE, 1_000, 150_000 + i * 1_000L)).getId());
        }
        return ids;
    }

    private Long balanceInDb() {
        // 테스트 트랜잭션은 커밋되지 않으므로, JPA 가 미뤄 둔 UPDATE 를 먼저 DB 에 내보낸 뒤 원본을 읽는다
        entityManager.flush();
        return jdbc.queryForObject("SELECT balance FROM points WHERE user_id = ?", Long.class, user.getId());
    }

    private Long historyCount() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM point_histories WHERE user_id = ?", Long.class, user.getId());
    }
}
