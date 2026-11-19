package com.coffeeorder.domain.menu.repository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

/**
 * 인기 메뉴 판매 수량(Redis). 날짜별 Sorted Set popular:menu:{yyyyMMdd}, 멤버 = 메뉴 ID, 점수 = 판매 수량(노션 5-4).
 * 키는 그 날짜로부터 8일 뒤 자정(서울)에 만료돼, 7일 합산에 필요한 날짜만 남는다.
 */
@Repository
@RequiredArgsConstructor
public class PopularMenuRepository {

    static final String KEY_PREFIX = "popular:menu:";
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int KEEP_DAYS = 8;

    private final StringRedisTemplate redisTemplate;

    static String key(LocalDate date) {
        return KEY_PREFIX + date.format(KEY_DATE);
    }

    /** 그 날짜 키에 메뉴별 판매 수량을 더한다. ZINCRBY 는 원자적이라 동시 주문이 겹쳐도 수량이 사라지지 않는다. */
    public void increase(LocalDate date, Map<Long, Integer> quantities) {
        String key = key(date);
        quantities.forEach((menuId, quantity) ->
                redisTemplate.opsForZSet().incrementScore(key, menuId.toString(), quantity));
        redisTemplate.expireAt(key, date.plusDays(KEEP_DAYS).atStartOfDay(SEOUL).toInstant());
    }

    /** today 를 포함한 최근 days 일의 메뉴별 판매 수량 합계. 키가 없는 날은 건너뛴다. */
    public Map<Long, Long> sumRecent(LocalDate today, int days) {
        Map<Long, Long> totals = new HashMap<>();
        for (int i = 0; i < days; i++) {
            Set<TypedTuple<String>> entries = redisTemplate.opsForZSet().rangeWithScores(key(today.minusDays(i)), 0, -1);
            if (entries == null) {
                continue;
            }
            for (TypedTuple<String> entry : entries) {
                totals.merge(Long.valueOf(entry.getValue()), entry.getScore().longValue(), Long::sum);
            }
        }
        return totals;
    }
}
