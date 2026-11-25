package com.coffeeorder.fault;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coffeeorder.domain.cart.service.CartService;
import com.coffeeorder.domain.order.event.OrderDataSender;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 장애 상황: Redis 가 꺼져 있을 때 주문·환불·인기 메뉴 조회가 어떻게 되는지 실제 HTTP 요청으로 확인한다(노션 EXP-8).
 * 개발 Redis 를 끄지 않고, 아무도 듣지 않는 포트를 Redis 주소로 줘서 접속이 거절되게 한다.
 * 커밋 후 처리(인기 메뉴 반영·주문 데이터 전송)는 커밋돼야 돌기 때문에 롤백형 테스트가 아니다 →
 * 테스트 전용 스키마(coffee_order_test)에 커밋하고, 만든 사용자와 그 데이터를 끝나고 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RedisDownTest {

    @DynamicPropertySource
    static void 닫힌_포트로_Redis_연결(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { // 비어 있는 포트를 하나 받아 바로 닫는다
            closedPort = socket.getLocalPort();
        }
        registry.add("spring.data.redis.port", () -> closedPort);
    }

    @MockitoSpyBean OrderDataSender orderDataSender;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CartService cartService;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> createdUsers = new ArrayList<>();

    @AfterEach
    void 만든_데이터_지우기() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM point_histories WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM points WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUsers.clear();
    }

    @Test
    void Redis_가_꺼져도_주문은_성공하고_주문_데이터는_전송된다(CapturedOutput output) throws Exception {
        long userId = createUser(10_000);
        cartService.add(userId, menuId("아메리카노"), 2); // 9,000원

        MockHttpServletResponse res = order(userId);

        assertThat(res.getStatus()).isEqualTo(201);
        long orderId = orderId(res);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId)).isEqualTo("PAID");
        assertThat(balance(userId)).isEqualTo(1_000L);
        assertThat(count("SELECT COUNT(*) FROM point_histories WHERE user_id = ? AND type = 'USE'", userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", userId)).isZero();
        verify(orderDataSender, times(1)).send(any()); // 인기 메뉴 반영이 실패해도 전송은 따로 나간다
        assertThat(output).contains("인기 메뉴 반영 실패: orderId=" + orderId);
    }

    @Test
    void Redis_와_주문_데이터_전송이_함께_실패해도_주문은_성공한다(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException("전송 서버 다운")).when(orderDataSender).send(any());
        long userId = createUser(10_000);
        cartService.add(userId, menuId("아메리카노"), 1);

        MockHttpServletResponse res = order(userId);

        assertThat(res.getStatus()).isEqualTo(201);
        long orderId = orderId(res);
        assertThat(balance(userId)).isEqualTo(5_500L);
        assertThat(output).contains("인기 메뉴 반영 실패: orderId=" + orderId, "주문 데이터 전송 실패: orderId=" + orderId);
    }

    @Test
    void Redis_가_꺼져도_환불은_성공한다(CapturedOutput output) throws Exception {
        long userId = createUser(10_000);
        cartService.add(userId, menuId("아메리카노"), 1);
        long orderId = orderId(order(userId));

        MockHttpServletResponse res = mockMvc.perform(post("/api/users/{id}/orders/{orderId}/refund", userId, orderId))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("REFUNDED");
        assertThat(balance(userId)).isEqualTo(10_000L);
        assertThat(output).contains("인기 메뉴 차감 실패: orderId=" + orderId);
    }

    @Test
    void Redis_가_꺼지면_인기_메뉴_조회는_500_INTERNAL_SERVER_ERROR() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/menus/popular")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 오류가 발생했습니다.\"}");
    }

    private MockHttpServletResponse order(long userId) throws Exception {
        return mockMvc.perform(post("/api/users/{id}/orders", userId)).andReturn().getResponse();
    }

    private long orderId(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).isEqualTo(201);
        return objectMapper.readTree(res.getContentAsString(UTF_8)).path("data").path("orderId").asLong();
    }

    private long createUser(long balance) {
        User user = userRepository.save(new User("장애테스터"));
        pointRepository.save(new Point(user, balance));
        createdUsers.add(user.getId());
        return user.getId();
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }

    private long balance(long userId) {
        return jdbc.queryForObject("SELECT balance FROM points WHERE user_id = ?", Long.class, userId);
    }

    private long count(String sql, long userId) {
        return jdbc.queryForObject(sql, Long.class, userId);
    }
}
