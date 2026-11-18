package com.coffeeorder.domain.cart;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

/**
 * 노션 테스트 케이스 A(담기)·L(조회)·U(수량 변경)·D(삭제)를 실제 MySQL 에서 확인한다.
 * 테스트마다 자기 사용자를 만들고 롤백한다. 메뉴는 시드 메뉴를 이름으로 찾는다(ID 하드코딩 금지).
 * 재시도 규칙은 CartRetryExecutorTest, 컨트롤러 → 재시도 → 409 배선은 CartRetryWiringTest 에서 확인한다.
 * 실제 스레드 동시 충돌과 서비스 트랜잭션 자체(X-5, X-6)는 커밋이 필요해 11/20 동시성 테스트(D-10)에서 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CartApiTest {

    private static final String ADDED = "{\"status\":200,\"message\":\"장바구니에 담겼습니다.\",\"data\":null}";
    private static final String REMOVED = "{\"status\":200,\"message\":\"장바구니에서 삭제되었습니다.\",\"data\":null}";
    private static final String INVALID_QUANTITY =
            "{\"status\":400,\"code\":\"INVALID_QUANTITY\",\"message\":\"수량이 올바르지 않습니다.\"}";
    private static final String INVALID_REQUEST =
            "{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"요청 값이 올바르지 않습니다.\"}";
    private static final String USER_NOT_FOUND =
            "{\"status\":404,\"code\":\"USER_NOT_FOUND\",\"message\":\"사용자를 찾을 수 없습니다.\"}";
    private static final String MENU_NOT_FOUND =
            "{\"status\":404,\"code\":\"MENU_NOT_FOUND\",\"message\":\"메뉴를 찾을 수 없습니다.\"}";
    private static final String CART_ITEM_NOT_FOUND =
            "{\"status\":404,\"code\":\"CART_ITEM_NOT_FOUND\",\"message\":\"장바구니 항목을 찾을 수 없습니다.\"}";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private long userId;
    private long missingUserId;
    private long americano;   // 4,500원
    private long cloudLatte;  // 6,000원
    private long missingMenuId;

    @BeforeEach
    void 준비() {
        userId = userRepository.saveAndFlush(new User("장바구니테스터")).getId();
        missingUserId = userId + 100_000;
        americano = menuId("아메리카노");
        cloudLatte = menuId("폭신폭신 구름과자 라떼");
        missingMenuId = jdbc.queryForObject("SELECT MAX(id) FROM menus", Long.class) + 100_000;
    }

    // ---- 담기 A ----

    @Test
    void A1_새_메뉴_담기() throws Exception {
        MockHttpServletResponse res = add(userId, americano, "2");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(ADDED);
        assertThat(quantityInDb(americano)).isEqualTo(2);
    }

    @Test
    void A2_같은_메뉴를_다시_담으면_한_행에_수량을_합친다() throws Exception {
        add(userId, americano, "2");
        add(userId, americano, "3");

        assertThat(rowCount()).isEqualTo(1L);
        assertThat(quantityInDb(americano)).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "-1"})
    void A3_수량_범위_밖이면_INVALID_QUANTITY(String quantity) throws Exception {
        MockHttpServletResponse res = add(userId, americano, quantity);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_QUANTITY);
        assertThat(rowCount()).isZero();
    }

    @Test
    void 수량_1과_99는_담긴다() throws Exception {
        assertThat(add(userId, americano, "1").getStatus()).isEqualTo(200);
        assertThat(add(userId, cloudLatte, "99").getStatus()).isEqualTo(200);
        assertThat(quantityInDb(cloudLatte)).isEqualTo(99);
    }

    @Test
    void A4_합친_결과가_99를_넘으면_INVALID_QUANTITY이고_기존_수량은_그대로다() throws Exception {
        add(userId, americano, "98");

        MockHttpServletResponse res = add(userId, americano, "2");

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_QUANTITY);
        assertThat(quantityInDb(americano)).isEqualTo(98);
    }

    @Test
    void 합친_결과가_정확히_99면_담긴다() throws Exception {
        add(userId, americano, "98");

        assertThat(add(userId, americano, "1").getStatus()).isEqualTo(200);
        assertThat(quantityInDb(americano)).isEqualTo(99);
    }

    @Test
    void A5_없는_메뉴는_MENU_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = add(userId, missingMenuId, "1");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(MENU_NOT_FOUND);
    }

    @Test
    void A6_없는_사용자는_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = add(missingUserId, americano, "1");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"menuId\":%d,\"quantity\":\"abc\"}", "{\"menuId\":%d}", "{\"menuId\":%d,\"quantity\":null}",
            "{\"menuId\":%d,\"quantity\":1.5}", "{\"quantity\":1}", "{\"menuId\":%d,\"quantity\":"})
    void A7_수량_문자_누락_소수_메뉴_누락_깨진_JSON은_INVALID_REQUEST(String template) throws Exception {
        MockHttpServletResponse res = postJson(userId, template.formatted(americano));

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
        assertThat(rowCount()).isZero();
    }

    // ---- 조회 L ----

    @Test
    void L1_장바구니_조회는_담은_순서와_합계를_준다() throws Exception {
        add(userId, cloudLatte, "1");
        add(userId, americano, "2");

        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/cart", userId)).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":200,\"message\":\"장바구니 조회 성공\",\"data\":{\"items\":["
                        + "{\"menuId\":" + cloudLatte
                        + ",\"name\":\"폭신폭신 구름과자 라떼\",\"price\":6000,\"quantity\":1,\"linePrice\":6000},"
                        + "{\"menuId\":" + americano
                        + ",\"name\":\"아메리카노\",\"price\":4500,\"quantity\":2,\"linePrice\":9000}"
                        + "],\"totalPrice\":15000}}");
    }

    @Test
    void L2_빈_장바구니는_빈_목록과_합계_0() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/cart", userId)).andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":200,\"message\":\"장바구니 조회 성공\",\"data\":{\"items\":[],\"totalPrice\":0}}");
    }

    @Test
    void 없는_사용자의_장바구니_조회는_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/cart", missingUserId))
                .andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    @Test
    void 다른_사용자의_장바구니는_섞이지_않는다() throws Exception {
        long other = userRepository.saveAndFlush(new User("다른사용자")).getId();
        add(other, americano, "5");

        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/cart", userId)).andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).contains("\"items\":[]");
    }

    // ---- 수량 변경 U ----

    @Test
    void U1_수량_변경은_담기와_같은_문구이고_수량을_바꾸며_version이_오른다() throws Exception {
        add(userId, americano, "2");

        MockHttpServletResponse res = patchJson(userId, americano, "{\"quantity\":3}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(ADDED);
        assertThat(quantityInDb(americano)).isEqualTo(3);
        assertThat(versionInDb(americano)).isEqualTo(1L); // 낙관적 락 판 번호가 0 → 1
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "100"})
    void U2_변경_수량_범위_밖이면_INVALID_QUANTITY이고_그대로다(String quantity) throws Exception {
        add(userId, americano, "2");

        MockHttpServletResponse res = patchJson(userId, americano, "{\"quantity\":" + quantity + "}");

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_QUANTITY);
        assertThat(quantityInDb(americano)).isEqualTo(2);
    }

    @Test
    void U3_담지_않은_메뉴_변경은_CART_ITEM_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = patchJson(userId, americano, "{\"quantity\":3}");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CART_ITEM_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":null}", "{\"quantity\":\"abc\"}", "{\"quantity\":2.5}"})
    void 변경_수량_누락_문자_소수는_INVALID_REQUEST(String body) throws Exception {
        add(userId, americano, "2");

        MockHttpServletResponse res = patchJson(userId, americano, body);

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
        assertThat(quantityInDb(americano)).isEqualTo(2);
    }

    @Test
    void 없는_사용자의_수량_변경은_USER_NOT_FOUND() throws Exception {
        assertThat(patchJson(missingUserId, americano, "{\"quantity\":3}").getContentAsString(UTF_8))
                .isEqualTo(USER_NOT_FOUND);
    }

    // ---- 삭제 D ----

    @Test
    void D1_삭제하면_조회에서_사라진다() throws Exception {
        add(userId, americano, "2");
        add(userId, cloudLatte, "1");

        MockHttpServletResponse res = mockMvc.perform(delete("/api/users/{id}/cart/items/{m}", userId, americano))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(REMOVED);
        assertThat(rowCount()).isEqualTo(1L);
        assertThat(quantityInDb(cloudLatte)).isEqualTo(1);
    }

    @Test
    void D2_담지_않은_메뉴_삭제는_CART_ITEM_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(delete("/api/users/{id}/cart/items/{m}", userId, americano))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CART_ITEM_NOT_FOUND);
    }

    @Test
    void 메뉴_번호_자리에_문자면_INVALID_REQUEST() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(delete("/api/users/{id}/cart/items/abc", userId))
                .andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INVALID_REQUEST);
    }

    @Test
    void JSON_응답을_받지_않는_담기는_실행하지_않는다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/users/{id}/cart/items", userId)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                        .content("{\"menuId\":" + americano + ",\"quantity\":1}"))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(406);
        assertThat(rowCount()).isZero();
    }

    // ---- 사용자 격리 (인증이 없어 userId 조건이 유일한 격리 장치) ----

    @Test
    void 같은_메뉴를_두_사용자가_담으면_행이_따로_생긴다() throws Exception {
        long other = userRepository.saveAndFlush(new User("다른사용자")).getId();
        add(other, americano, "5");
        add(userId, americano, "1");

        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cart_items WHERE menu_id = ? AND user_id IN (?, ?)", Long.class,
                americano, userId, other)).isEqualTo(2L);
        assertThat(quantityInDb(americano)).isEqualTo(1);
    }

    @Test
    void 남의_장바구니_항목은_수정도_삭제도_되지_않는다() throws Exception {
        long other = userRepository.saveAndFlush(new User("다른사용자")).getId();
        add(other, americano, "5");

        assertThat(patchJson(userId, americano, "{\"quantity\":3}").getContentAsString(UTF_8))
                .isEqualTo(CART_ITEM_NOT_FOUND);
        assertThat(deleteItem(userId, americano).getContentAsString(UTF_8)).isEqualTo(CART_ITEM_NOT_FOUND);

        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?", Integer.class, other, americano))
                .isEqualTo(5);
    }

    @Test
    void 없는_사용자의_삭제는_USER_NOT_FOUND() throws Exception {
        assertThat(deleteItem(missingUserId, americano).getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    // ---- 낙관적 락·N+1 ----

    @Test
    void 다른_곳에서_먼저_고친_행을_수정하면_낙관적_락이_막는다() throws Exception {
        // 커밋 없이 @Version 동작을 확인한다: 같은 트랜잭션 안에서 DB 의 판 번호를 먼저 올려 두고 수정한다
        add(userId, americano, "2");
        entityManager.flush();
        jdbc.update("UPDATE cart_items SET version = version + 1 WHERE user_id = ? AND menu_id = ?", userId, americano);

        patchJson(userId, americano, "{\"quantity\":3}");

        assertThatThrownBy(() -> entityManager.flush()).isInstanceOf(OptimisticLockException.class);
    }

    @Test
    void 장바구니_조회는_항목_수와_상관없이_쿼리_2번이다() throws Exception {
        add(userId, americano, "1");
        add(userId, cloudLatte, "1");
        add(userId, menuId("졸린 곰돌이 우유"), "1");
        entityManager.flush();
        entityManager.clear(); // 이미 읽어 둔 메뉴가 있으면 N+1 이 가려진다
        Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        getCart(userId);

        assertThat(stats.getPrepareStatementCount()).isEqualTo(2L); // 사용자 확인 1 + 항목·메뉴 함께 1
    }

    // ---- helpers ----

    private MockHttpServletResponse getCart(long targetUserId) throws Exception {
        return mockMvc.perform(get("/api/users/{id}/cart", targetUserId)).andReturn().getResponse();
    }

    private MockHttpServletResponse deleteItem(long targetUserId, long menuId) throws Exception {
        return mockMvc.perform(delete("/api/users/{id}/cart/items/{m}", targetUserId, menuId))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse add(long targetUserId, long menuId, String quantity) throws Exception {
        return postJson(targetUserId, "{\"menuId\":" + menuId + ",\"quantity\":" + quantity + "}");
    }

    private MockHttpServletResponse postJson(long targetUserId, String body) throws Exception {
        return mockMvc.perform(
                        post("/api/users/{id}/cart/items", targetUserId)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse patchJson(long targetUserId, long menuId, String body) throws Exception {
        return mockMvc.perform(
                        patch("/api/users/{id}/cart/items/{m}", targetUserId, menuId)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }

    private Integer quantityInDb(long menu) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?", Integer.class, userId, menu);
    }

    private Long versionInDb(long menu) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT version FROM cart_items WHERE user_id = ? AND menu_id = ?", Long.class, userId, menu);
    }

    private Long rowCount() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", Long.class, userId);
    }
}
