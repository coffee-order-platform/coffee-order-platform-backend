package com.coffeeorder.domain.menu.repository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
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

    /**
     * 그 날짜 키에 메뉴별 판매 수량을 더한다. ZINCRBY 는 원자적이라 동시 주문이 겹쳐도 수량이 사라지지 않는다.
     * 점수와 만료는 명령이 따로라, 그 사이 Redis 가 끊기면 만료 없는 키가 남을 수 있다. 같은 날 다음 주문이 만료를 다시 걸고,
     * 조회는 7일치만 읽어 집계는 틀어지지 않는다(남는 것은 키 하나).
     */
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

    /**
     * 환불한 주문의 날짜 키에서 메뉴별 수량을 뺀다(노션 5-4). 0 아래로는 내리지 않고, 키(또는 메뉴)가 없으면 건너뛴다 —
     * 8일이 지나 만료된 키를 다시 만들지 않기 위해서다. 읽고·계산하고·쓰는 세 단계를 Lua 스크립트 하나로 Redis 안에서 한 번에 실행해,
     * 같은 메뉴의 주문(ZINCRBY)이 사이에 끼어도 수량이 사라지지 않는다. 기존 키에 ZADD 하므로 만료 시각은 그대로 남는다.
     */
    public void decrease(LocalDate date, Map<Long, Integer> quantities) {
        List<String> args = new ArrayList<>();
        quantities.forEach((menuId, quantity) -> {
            args.add(menuId.toString());
            args.add(quantity.toString());
        });
        redisTemplate.execute(DECREASE_SCRIPT, List.of(key(date)), args.toArray());
    }

    private static final RedisScript<Long> DECREASE_SCRIPT = RedisScript.of("""
            for i = 1, #ARGV, 2 do
              local score = redis.call('ZSCORE', KEYS[1], ARGV[i])
              if score then
                local next = tonumber(score) - tonumber(ARGV[i + 1])
                if next < 0 then next = 0 end
                redis.call('ZADD', KEYS[1], next, ARGV[i])
              end
            end
            return 0
            """, Long.class);
}
