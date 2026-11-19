package com.coffeeorder.domain.order;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coffeeorder.domain.cart.entity.CartItem;
import com.coffeeorder.domain.cart.repository.CartItemRepository;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.order.event.OrderCompletedEvent;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import com.coffeeorder.domain.order.service.OrderService;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 테스트 케이스 O(주문·결제)를 실제 MySQL 에서 확인한다. 테스트마다 자기 사용자를 만들고 끝나면 롤백된다.
 * 롤백이라 커밋 후 처리(Mock 전송)는 돌지 않는다 → 여기서는 이벤트 발행만 보고, 전송은 OrderEventListenerTest·OrderEventWiringTest 가 본다.
 * 실제 스레드 동시 주문(X-1, X-2)은 커밋이 필요해 11/20 동시성 테스트(D-12)에서 다룬다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class OrderApiTest {

    private static final String CART_EMPTY =
            "{\"status\":400,\"code\":\"CART_EMPTY\",\"message\":\"장바구니가 비어 있습니다.\"}";
    private static final String INSUFFICIENT_POINT =
            "{\"status\":400,\"code\":\"INSUFFICIENT_POINT\",\"message\":\"포인트가 부족합니다.\"}";
    private static final String USER_NOT_FOUND =
            "{\"status\":404,\"code\":\"USER_NOT_FOUND\",\"message\":\"사용자를 찾을 수 없습니다.\"}";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEvents events;

    private User user;
    private long americano;
    private long cloudLatte;

    @BeforeEach
    void 테스트_사용자() {
        user = createUser("주문테스터", 150_000);
        americano = menuId("아메리카노");
        cloudLatte = menuId("폭신폭신 구름과자 라떼");
    }

    @Test
    void O1_장바구니_전체를_결제하고_잔액_내역_주문_장바구니가_맞게_바뀐다() throws Exception {
        // 메뉴 번호가 큰 라떼를 먼저 담아, 항목이 메뉴 번호 순이 아니라 담은 순서로 나오는지 구분한다
        putInCart(user, cloudLatte, 1);
        putInCart(user, americano, 2);

        MockHttpServletResponse res = order(user.getId());

        assertThat(res.getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(res.getContentAsString(UTF_8));
        assertThat(body.path("status").asInt()).isEqualTo(201); // 본문 status 와 HTTP 상태가 같아야 한다
        assertThat(body.path("message").asString()).isEqualTo("주문이 완료되었습니다.");
        JsonNode data = body.path("data");
        assertThat(data.propertyNames()).containsExactly("orderId", "totalPrice", "balance", "orderedAt", "items");
        // 영수증: 장바구니가 비워지므로 주문 항목 스냅샷을 응답으로 돌려준다(담은 순서)
        assertThat(data.path("items").toString()).isEqualTo("["
                + "{\"menuId\":" + cloudLatte + ",\"menuName\":\"폭신폭신 구름과자 라떼\",\"unitPrice\":6000,\"quantity\":1,\"linePrice\":6000},"
                + "{\"menuId\":" + americano + ",\"menuName\":\"아메리카노\",\"unitPrice\":4500,\"quantity\":2,\"linePrice\":9000}]");
        long orderId = data.path("orderId").asLong();
        assertThat(data.path("totalPrice").asLong()).isEqualTo(15_000L); // 4,500×2 + 6,000
        assertThat(data.path("balance").asLong()).isEqualTo(135_000L);
        assertThat(data.path("orderedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

        entityManager.flush();
        assertThat(balanceInDb(user)).isEqualTo(135_000L);
        assertThat(jdbc.queryForMap("SELECT user_id, total_price, status FROM orders WHERE id = ?", orderId))
                .containsEntry("user_id", user.getId()).containsEntry("total_price", 15_000L)
                .containsEntry("status", "PAID");
        // 사용 내역 1건: 주문과 연결되고 처리 후 잔액이 남는다
        assertThat(jdbc.queryForList(
                "SELECT type, amount, balance_after, order_id FROM point_histories WHERE user_id = ?", user.getId()))
                .containsExactly(Map.of("type", "USE", "amount", 15_000L, "balance_after", 135_000L,
                        "order_id", orderId));
        assertThat(cartCount(user)).isZero();
    }

    @Test
    void O2_주문_항목에_주문_당시_메뉴_이름_단가_수량_금액을_저장한다() throws Exception {
        putInCart(user, americano, 2);
        putInCart(user, cloudLatte, 1);

        long orderId = orderId(order(user.getId()));

        entityManager.flush();
        assertThat(jdbc.queryForList("SELECT menu_id, menu_name, unit_price, quantity, line_price "
                + "FROM order_items WHERE order_id = ? ORDER BY id", orderId))
                .containsExactly(
                        Map.of("menu_id", americano, "menu_name", "아메리카노", "unit_price", 4_500L,
                                "quantity", 2, "line_price", 9_000L),
                        Map.of("menu_id", cloudLatte, "menu_name", "폭신폭신 구름과자 라떼", "unit_price", 6_000L,
                                "quantity", 1, "line_price", 6_000L));
    }

    @Test
    void O3_주문_뒤_메뉴_가격이_바뀌어도_주문_항목_가격은_그대로다() throws Exception {
        // 스냅샷은 별도 컬럼이라 스키마가 보장한다. 노션 O-3 시나리오를 그대로 고정해 둔다(코드 쪽은 O-2 가 잡는다)
        putInCart(user, americano, 1);
        long orderId = orderId(order(user.getId()));
        entityManager.flush();

        jdbc.update("UPDATE menus SET price = 9999, name = '바뀐 이름' WHERE id = ?", americano);

        assertThat(jdbc.queryForMap("SELECT menu_name, unit_price FROM order_items WHERE order_id = ?", orderId))
                .containsEntry("menu_name", "아메리카노").containsEntry("unit_price", 4_500L);
    }

    @Test
    void 가격은_장바구니에_담을_때가_아니라_주문할_때의_메뉴_가격이다() throws Exception {
        putInCart(user, americano, 1);
        entityManager.flush();
        jdbc.update("UPDATE menus SET price = 5000 WHERE id = ?", americano);

        JsonNode data = objectMapper.readTree(order(user.getId()).getContentAsString(UTF_8)).path("data");

        assertThat(data.path("totalPrice").asLong()).isEqualTo(5_000L);
        assertThat(data.path("balance").asLong()).isEqualTo(145_000L);
    }

    @Test
    void O4_빈_장바구니로_주문하면_CART_EMPTY_이고_아무것도_바뀌지_않는다() throws Exception {
        MockHttpServletResponse res = order(user.getId());

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CART_EMPTY);
        assertThat(balanceInDb(user)).isEqualTo(150_000L);
        assertThat(orderCount(user)).isZero();
        assertThat(events.stream(OrderCompletedEvent.class)).isEmpty();
    }

    @Test
    void O5_포인트가_부족하면_INSUFFICIENT_POINT_이고_잔액_장바구니_주문이_그대로다() throws Exception {
        User poor = createUser("빈지갑테스터", 2_000);
        putInCart(poor, americano, 1);

        MockHttpServletResponse res = order(poor.getId());

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(INSUFFICIENT_POINT);
        assertThat(balanceInDb(poor)).isEqualTo(2_000L);
        assertThat(cartCount(poor)).isEqualTo(1);
        assertThat(orderCount(poor)).isZero();
        assertThat(historyCount(poor)).isZero();
    }

    @Test
    void O6_잔액이_총액과_딱_같으면_주문되고_잔액은_0이다() throws Exception {
        User exact = createUser("딱맞는테스터", 4_500);
        putInCart(exact, americano, 1);

        MockHttpServletResponse res = order(exact.getId());

        assertThat(res.getStatus()).isEqualTo(201);
        assertThat(balanceInDb(exact)).isZero();
    }

    @Test
    void O7_없는_사용자는_USER_NOT_FOUND() throws Exception {
        MockHttpServletResponse res = order(user.getId() + 100_000);

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(USER_NOT_FOUND);
    }

    @Test
    void 주문이_끝나면_전송할_내용을_담은_이벤트를_1건_발행한다() throws Exception {
        putInCart(user, americano, 2);
        putInCart(user, cloudLatte, 1);

        long orderId = orderId(order(user.getId()));

        List<OrderCompletedEvent> published = events.stream(OrderCompletedEvent.class).toList();
        assertThat(published).hasSize(1);
        OrderCompletedEvent event = published.get(0);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.userId()).isEqualTo(user.getId());
        // 인기 메뉴는 판매 수량만큼 더하므로 수량까지 담는다
        assertThat(event.menus()).containsExactly(new OrderedMenu(americano, 2), new OrderedMenu(cloudLatte, 1));
        assertThat(event.totalPrice()).isEqualTo(15_000L);
        // 인기 메뉴 날짜 키는 주문 시각으로 정한다(응답의 orderedAt 과 같은 값)
        assertThat(event.orderedAt()).isCloseTo(jdbc.queryForObject( // DB 는 마이크로초로 반올림해 저장한다
                "SELECT created_at FROM orders WHERE id = ?", LocalDateTime.class, orderId), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void O9_실패한_주문은_이벤트를_발행하지_않는다() throws Exception {
        User poor = createUser("빈지갑테스터", 2_000);
        putInCart(poor, americano, 1);

        order(poor.getId());

        assertThat(events.stream(OrderCompletedEvent.class)).isEmpty();
    }

    @Test
    void 주문한_사용자의_장바구니만_비운다() throws Exception {
        User other = createUser("다른사용자", 150_000);
        putInCart(user, americano, 1);
        putInCart(other, americano, 3);

        order(user.getId());

        assertThat(cartCount(user)).isZero();
        assertThat(cartCount(other)).isEqualTo(1);
        assertThat(balanceInDb(other)).isEqualTo(150_000L);
    }

    @Test
    void 주문_서비스에는_트랜잭션이_걸려_있다() throws Exception {
        // 이 테스트 클래스는 자체 트랜잭션 안에서 돌아서, 서비스의 @Transactional 이 빠져도 다른 테스트가 알아채지 못한다
        Transactional tx = OrderService.class.getMethod("order", Long.class).getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    void 주문용_장바구니_조회는_행을_잠근다() throws Exception {
        // 실제 잠금 대기는 커밋이 필요해 11/20 에 본다. 여기서는 잠금 쿼리가 배타 락인지 고정한다
        Lock lock = CartItemRepository.class.getMethod("findAllByUserIdForUpdate", Long.class)
                .getAnnotation(Lock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void E2_주문_주소에_GET은_405이고_Allow_헤더가_POST다() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/users/{id}/orders", user.getId()))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(405);
        assertThat(res.getHeader("Allow")).contains("POST");
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":405,\"code\":\"METHOD_NOT_ALLOWED\",\"message\":\"지원하지 않는 요청 방식입니다.\"}");
    }

    @Test
    void 사용자_번호_자리에_문자면_INVALID_REQUEST() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/users/abc/orders")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":400,\"code\":\"INVALID_REQUEST\",\"message\":\"요청 값이 올바르지 않습니다.\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "application/json;q=0"})
    void JSON_응답을_받지_않는_주문은_실행하지_않는다(String accept) throws Exception {
        putInCart(user, americano, 1);

        MockHttpServletResponse res = mockMvc.perform(
                        post("/api/users/{id}/orders", user.getId()).header(HttpHeaders.ACCEPT, accept))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(406);
        assertThat(balanceInDb(user)).isEqualTo(150_000L);
        assertThat(cartCount(user)).isEqualTo(1);
        assertThat(orderCount(user)).isZero();
    }

    // ---- 도우미 ----

    private User createUser(String name, long balance) {
        User created = userRepository.saveAndFlush(new User(name));
        pointRepository.saveAndFlush(new Point(created, balance));
        return created;
    }

    private void putInCart(User owner, long menuId, int quantity) {
        cartItemRepository.saveAndFlush(new CartItem(owner, menuRepository.getReferenceById(menuId), quantity));
    }

    private MockHttpServletResponse order(long userId) throws Exception {
        return mockMvc.perform(post("/api/users/{id}/orders", userId)).andReturn().getResponse();
    }

    private long orderId(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(201);
        return objectMapper.readTree(res.getContentAsString(UTF_8)).path("data").path("orderId").asLong();
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }

    private long balanceInDb(User owner) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT balance FROM points WHERE user_id = ?", Long.class, owner.getId());
    }

    private long cartCount(User owner) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", Long.class, owner.getId());
    }

    private long orderCount(User owner) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, owner.getId());
    }

    private long historyCount(User owner) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_histories WHERE user_id = ?", Long.class, owner.getId());
    }
}
