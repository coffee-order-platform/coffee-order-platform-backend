package com.coffeeorder.domain.menu;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실험용 인기 메뉴 DB 집계 API(EXP-4)를 켠 상태에서, Redis 방식과 같은 규칙으로 집계하는지 본다.
 * 시계를 2097-06-30(서울)로 고정해 다른 테스트의 주문·키(2099-03, 오늘)와 겹치지 않게 하고, 만든 주문·키는 테스트마다 지운다.
 * 꺼진 상태의 404 는 PopularMenuApiTest 가 본다.
 */
@SpringBootTest(properties = "experiment.popular-db.enabled=true")
@AutoConfigureMockMvc
class PopularMenuDbApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2097, 6, 30);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            // 서울 06-30 01:00 = UTC 로는 아직 06-29. 날짜를 서울이 아닌 시간대로 계산하면 기간이 하루 밀려 테스트가 실패한다
            return Clock.fixed(Instant.parse("2097-06-29T16:00:00Z"), SEOUL);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired PopularMenuRepository popularMenuRepository;
    @Autowired UserRepository userRepository;

    private long userId;
    private long americano;
    private long cloudLatte;
    private long bearMilk;
    private long vanilla;

    @BeforeEach
    void 준비() {
        // 이전 실행이 강제 종료돼 남긴 2097-06 주문부터 지운다. DB 집계는 사용자로 거르지 않아서 남으면 결과가 틀어진다
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE created_at >= '2097-06-01' "
                + "AND created_at < '2097-08-01')");
        jdbc.update("DELETE FROM orders WHERE created_at >= '2097-06-01' AND created_at < '2097-08-01'");
        jdbc.update("DELETE FROM users WHERE name = '인기메뉴DB테스터'");
        userId = userRepository.save(new User("인기메뉴DB테스터")).getId();
        americano = menuId("아메리카노");
        cloudLatte = menuId("폭신폭신 구름과자 라떼");
        bearMilk = menuId("졸린 곰돌이 우유");
        vanilla = menuId("번개맞은 바닐라");
        deleteTestKeys();
    }

    @AfterEach
    void 만든_데이터_지우기() {
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        deleteTestKeys();
    }

    @Test
    void 오늘_포함_7일_결제_완료만_합산하고_동점은_메뉴_ID_작은_순_3개까지_Redis_와_같은_결과() throws Exception {
        // 라떼: 8일째 마지막 순간(제외) 5개, 오늘 마지막 초(포함) 1개, 2일 전 2개 → 여러 날 합산 3개로 1위
        order("PAID", TODAY.minusDays(7).atTime(23, 59, 59, 999_999_000), cloudLatte, 5);
        order("PAID", TODAY.atTime(23, 59, 59), cloudLatte, 1);
        order("PAID", TODAY.minusDays(2).atTime(15, 0), cloudLatte, 2);
        // 아메리카노: 7일째 0시 정각(포함) 2개
        order("PAID", TODAY.minusDays(6).atStartOfDay(), americano, 2);
        // 곰돌이 우유: 환불(제외) 10개, 결제 완료 2개 → 아메리카노와 동점
        order("REFUNDED", TODAY.minusDays(3).atTime(9, 0), bearMilk, 10);
        order("PAID", TODAY.minusDays(3).atTime(10, 0), bearMilk, 2);
        // 바닐라: 환불 3개, 내일 0시(제외) 9개, 결제 완료 1개 → 4위라 빠진다
        order("REFUNDED", TODAY.atTime(8, 0), vanilla, 3);
        order("PAID", TODAY.plusDays(1).atStartOfDay(), vanilla, 9);
        order("PAID", TODAY.minusDays(1).atTime(9, 0), vanilla, 1);

        long tieFirst = Math.min(americano, bearMilk);
        long tieSecond = Math.max(americano, bearMilk);
        assertThat(body("/api/menus/popular/db")).containsSubsequence(
                "\"rank\":1,\"menuId\":" + cloudLatte, "\"salesCount\":3",
                "\"rank\":2,\"menuId\":" + tieFirst, "\"salesCount\":2",
                "\"rank\":3,\"menuId\":" + tieSecond, "\"salesCount\":2")
                .doesNotContain("\"menuId\":" + vanilla, "\"rank\":4");

        // 같은 판매를 Redis 에 넣으면(환불분은 Redis 에서 이미 빠진 상태) 기존 API 와 응답이 글자까지 같아야 한다
        popularMenuRepository.increase(TODAY.minusDays(7), Map.of(cloudLatte, 5));
        popularMenuRepository.increase(TODAY, Map.of(cloudLatte, 1));
        popularMenuRepository.increase(TODAY.minusDays(2), Map.of(cloudLatte, 2));
        popularMenuRepository.increase(TODAY.minusDays(6), Map.of(americano, 2));
        popularMenuRepository.increase(TODAY.minusDays(3), Map.of(bearMilk, 2));
        popularMenuRepository.increase(TODAY.plusDays(1), Map.of(vanilla, 9));
        popularMenuRepository.increase(TODAY.minusDays(1), Map.of(vanilla, 1));
        assertThat(body("/api/menus/popular/db")).isEqualTo(body("/api/menus/popular"));
    }

    @Test
    void 기간_안에_결제_완료_주문이_없으면_빈_목록_한_종류면_1개_합계_0은_뺀다() throws Exception {
        order("REFUNDED", TODAY.atTime(9, 0), americano, 1);
        assertThat(body("/api/menus/popular/db")).isEqualTo("{\"status\":200,\"message\":null,\"data\":[]}");

        order("PAID", TODAY.atTime(10, 0), americano, 1);
        order("PAID", TODAY.atTime(11, 0), vanilla, 0); // 합계 0 (Redis 에서 환불로 0 이 된 메뉴와 같은 경우)
        assertThat(body("/api/menus/popular/db")).contains("\"rank\":1,\"menuId\":" + americano)
                .doesNotContain("\"rank\":2", "\"menuId\":" + vanilla);
    }

    // ---- 도우미 ----

    private void order(String status, LocalDateTime createdAt, long menuId, int quantity) {
        jdbc.update("INSERT INTO orders (user_id, total_price, status, created_at) VALUES (?, ?, ?, ?)",
                userId, 1000L * quantity, status, createdAt);
        long orderId = jdbc.queryForObject("SELECT MAX(id) FROM orders WHERE user_id = ?", Long.class, userId);
        jdbc.update("INSERT INTO order_items (order_id, menu_id, menu_name, unit_price, quantity, line_price) "
                + "SELECT ?, id, name, price, ?, price * ? FROM menus WHERE id = ?", orderId, quantity, quantity, menuId);
    }

    private void deleteTestKeys() {
        for (int day = 20; day <= 30; day++) { // 2097-06-20 ~ 07-01
            redisTemplate.delete(key(LocalDate.of(2097, 6, day)));
        }
        redisTemplate.delete(key(LocalDate.of(2097, 7, 1)));
    }

    private static String key(LocalDate date) {
        return "popular:menu:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String body(String path) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get(path)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return res.getContentAsString(UTF_8);
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }
}
