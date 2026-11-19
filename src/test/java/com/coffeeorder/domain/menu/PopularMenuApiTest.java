package com.coffeeorder.domain.menu;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 노션 테스트 케이스 P(인기 메뉴)를 실제 Redis 에서 확인한다.
 * 개발 Redis 의 실제 날짜 키와 겹치지 않게 시계를 2099-03-10(서울)로 고정하고, 쓴 키는 테스트마다 지운다.
 * 주문 → 커밋 → 반영 흐름은 OrderEventWiringTest 가 본다. 여기서는 저장된 판매 수량으로 조회 규칙을 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PopularMenuApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2099, 3, 10);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2099-03-10T03:00:00Z"), SEOUL); // 서울 12:00
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PopularMenuRepository popularMenuRepository;

    private long americano;
    private long cloudLatte;
    private long bearMilk;
    private long vanilla;

    @BeforeEach
    void 메뉴() {
        americano = menuId("아메리카노");
        cloudLatte = menuId("폭신폭신 구름과자 라떼");
        bearMilk = menuId("졸린 곰돌이 우유");
        vanilla = menuId("번개맞은 바닐라");
        deleteTestKeys();
    }

    @AfterEach
    void 테스트_키_삭제() {
        deleteTestKeys();
    }

    @Test
    void P1_판매_기록이_없으면_빈_목록() throws Exception {
        assertThat(body()).isEqualTo("{\"status\":200,\"message\":\"인기 메뉴 조회 성공\",\"data\":[]}");
    }

    @Test
    void 응답은_순위_메뉴_판매_수량을_담는다() throws Exception {
        popularMenuRepository.increase(TODAY, Map.of(americano, 3));

        assertThat(body()).isEqualTo("{\"status\":200,\"message\":\"인기 메뉴 조회 성공\",\"data\":["
                + "{\"rank\":1,\"menuId\":" + americano + ",\"name\":\"아메리카노\",\"price\":4500,\"salesCount\":3}]}");
    }

    @Test
    void P2_두_종류만_팔렸으면_2개만_돌려준다() throws Exception {
        popularMenuRepository.increase(TODAY, Map.of(americano, 1, cloudLatte, 2));

        assertThat(body()).contains("\"rank\":1,\"menuId\":" + cloudLatte, "\"rank\":2,\"menuId\":" + americano)
                .doesNotContain("\"rank\":3");
    }

    @Test
    void P3_주문_건수가_아니라_판매_수량으로_순위를_매긴다() throws Exception {
        // A(라떼) 3개 주문 1건 vs B(아메리카노) 1개씩 주문 2건 → A 가 1위(3 > 2)
        popularMenuRepository.increase(TODAY, Map.of(cloudLatte, 3));
        popularMenuRepository.increase(TODAY, Map.of(americano, 1));
        popularMenuRepository.increase(TODAY, Map.of(americano, 1));

        assertThat(body()).containsSubsequence(
                "\"rank\":1,\"menuId\":" + cloudLatte, "\"salesCount\":3",
                "\"rank\":2,\"menuId\":" + americano, "\"salesCount\":2");
    }

    @Test
    void P4_판매_수량이_같으면_메뉴_ID_가_작은_순() throws Exception {
        popularMenuRepository.increase(TODAY, Map.of(vanilla, 2, americano, 2));

        assertThat(americano).isLessThan(vanilla);
        assertThat(body()).containsSubsequence(
                "\"rank\":1,\"menuId\":" + americano, "\"rank\":2,\"menuId\":" + vanilla);
    }

    @Test
    void P5_오늘_포함_7일만_합산하고_8일_전은_뺀다() throws Exception {
        popularMenuRepository.increase(TODAY.minusDays(6), Map.of(americano, 1)); // 7일째(포함)
        popularMenuRepository.increase(TODAY.minusDays(7), Map.of(cloudLatte, 50)); // 8일째(제외)
        popularMenuRepository.increase(TODAY.plusDays(1), Map.of(bearMilk, 50)); // 내일(제외)

        assertThat(body()).contains("\"menuId\":" + americano)
                .doesNotContain("\"menuId\":" + cloudLatte, "\"menuId\":" + bearMilk);
    }

    @Test
    void 여러_날의_판매_수량을_합친다() throws Exception {
        popularMenuRepository.increase(TODAY, Map.of(americano, 1, cloudLatte, 3));
        popularMenuRepository.increase(TODAY.minusDays(3), Map.of(americano, 4));

        assertThat(body()).containsSubsequence(
                "\"rank\":1,\"menuId\":" + americano, "\"salesCount\":5",
                "\"rank\":2,\"menuId\":" + cloudLatte, "\"salesCount\":3");
    }

    @Test
    void 상위_3개까지만_돌려준다() throws Exception {
        popularMenuRepository.increase(TODAY, Map.of(americano, 4, cloudLatte, 3, bearMilk, 2, vanilla, 1));

        assertThat(body()).contains("\"rank\":3,\"menuId\":" + bearMilk)
                .doesNotContain("\"menuId\":" + vanilla, "\"rank\":4");
    }

    @Test
    void 판매_수량_합계가_0인_메뉴는_순위에서_뺀다() throws Exception {
        // 환불로 차감돼 0 이 된 경우(환불 PR 에서 차감 구현)
        redisTemplate.opsForZSet().add(PopularMenuRepositoryKeys.today(), Long.toString(americano), 0);
        popularMenuRepository.increase(TODAY, Map.of(cloudLatte, 1));

        assertThat(body()).contains("\"menuId\":" + cloudLatte).doesNotContain("\"menuId\":" + americano);
    }

    @Test
    void 날짜_키는_그날로부터_8일_뒤_자정에_만료된다() {
        popularMenuRepository.increase(TODAY, Map.of(americano, 1));

        Long ttlSeconds = redisTemplate.getExpire(PopularMenuRepositoryKeys.today());
        long expected = TODAY.plusDays(8).atStartOfDay(SEOUL).toEpochSecond() - Instant.now().getEpochSecond();
        assertThat(ttlSeconds).isBetween(expected - 60, expected + 60);
    }

    @Test
    void P7_Redis_장애면_500_INTERNAL_SERVER_ERROR() throws Exception {
        doThrow(new RedisConnectionFailureException("Redis 다운")).when(popularMenuRepository).sumRecent(any(), anyInt());

        MockHttpServletResponse res = mockMvc.perform(get("/api/menus/popular")).andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(
                "{\"status\":500,\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 오류가 발생했습니다.\"}");
    }

    // ---- 도우미 ----

    /** 테스트가 쓰는 날짜 키(2099-03-01 ~ 2099-03-12). */
    private static final class PopularMenuRepositoryKeys {
        static String of(LocalDate date) {
            return "popular:menu:" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        }

        static String today() {
            return of(TODAY);
        }
    }

    private void deleteTestKeys() {
        for (int day = 1; day <= 12; day++) {
            redisTemplate.delete(PopularMenuRepositoryKeys.of(LocalDate.of(2099, 3, day)));
        }
    }

    private String body() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/menus/popular")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        return res.getContentAsString(UTF_8);
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ? ORDER BY id LIMIT 1", Long.class, name);
    }
}
