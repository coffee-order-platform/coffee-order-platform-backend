package com.coffeeorder.domain.point;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
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
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
        List<PointHistory> histories = pointHistoryRepository.findAll().stream()
                .filter(h -> h.getUser().getId().equals(user.getId())).toList();
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getType()).isEqualTo(PointHistoryType.CHARGE);
        assertThat(histories.get(0).getAmount()).isEqualTo(1_000L);
        assertThat(histories.get(0).getBalanceAfter()).isEqualTo(151_000L);
        assertThat(histories.get(0).getOrder()).isNull();
    }

    @Test
    void C2_최대_금액_충전은_성공한다() throws Exception {
        MockHttpServletResponse res = charge(user.getId(), "{\"amount\":500000}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(balanceInDb()).isEqualTo(650_000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {999, 0, -1000, 500_001, 1_500})
    void C3_C4_C5_범위_밖이거나_1000원_단위가_아니면_INVALID_CHARGE_AMOUNT_이고_잔액과_내역은_그대로다(long amount)
            throws Exception {
        MockHttpServletResponse res = charge(user.getId(), "{\"amount\":" + amount + "}");

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_CHARGE_AMOUNT);
        assertThat(balanceInDb()).isEqualTo(150_000L);
        assertThat(historyCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"amount\":null}", "{\"amount\":\"abc\"}", "{\"amount\":"})
    void C6_E4_금액_누락_문자_깨진_JSON은_INVALID_REQUEST(String body) throws Exception {
        MockHttpServletResponse res = charge(user.getId(), body);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
        assertThat(balanceInDb()).isEqualTo(150_000L);
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
