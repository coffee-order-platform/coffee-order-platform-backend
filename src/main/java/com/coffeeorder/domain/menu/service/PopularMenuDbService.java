package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.dto.PopularMenuResponse;
import com.coffeeorder.domain.order.entity.OrderStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 실험용(노션 성능·동작 실험 EXP-4): 인기 메뉴를 Redis 대신 DB 에서 바로 집계한다. Redis 방식(PopularMenuService)이 왜 필요한지
 * 같은 조건에서 재기 위한 비교 대상이다. 평소에는 빈이 없고(experiment.popular-db.enabled=true 일 때만), 그래서 주소도 404 다.
 * 규칙은 PopularMenuService.getTop3 와 같다: 오늘 포함 7일(서울, 주문일 기준), 결제 완료(PAID)만, 판매 수량 합계 내림차순,
 * 동점이면 메뉴 ID 작은 순, 합계 0 은 빼고, DB 에 있는 메뉴만, 3개까지. 환불은 Redis 에서 빼는 대신 여기서는 PAID 만 센다.
 * 기간은 DB 의 NOW() 가 아니라 서울 시계로 계산해 넘긴다(created_at 이 서울 벽시계로 저장되기 때문).
 */
@Service
@ConditionalOnProperty(name = "experiment.popular-db.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PopularMenuDbService {

    /** 실험 테스트가 같은 문장으로 EXPLAIN 을 뜨려고 공개한다. 파라미터: status, 시작 시각(포함), 끝 시각(제외). */
    public static final String TOP3_SQL = "SELECT m.id, m.name, m.price, SUM(oi.quantity) AS sales "
            + "FROM orders o JOIN order_items oi ON oi.order_id = o.id JOIN menus m ON m.id = oi.menu_id "
            + "WHERE o.status = ? AND o.created_at >= ? AND o.created_at < ? "
            + "GROUP BY m.id, m.name, m.price HAVING SUM(oi.quantity) > 0 "
            + "ORDER BY sales DESC, m.id ASC LIMIT " + PopularMenuService.TOP;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public List<PopularMenuResponse> getTop3() {
        LocalDate today = LocalDate.now(clock);
        List<PopularMenuResponse> result = new ArrayList<>();
        jdbc.query(TOP3_SQL, rs -> {
            result.add(new PopularMenuResponse(result.size() + 1, rs.getLong("id"), rs.getString("name"),
                    rs.getLong("price"), rs.getLong("sales")));
        }, OrderStatus.PAID.name(), today.minusDays(PopularMenuService.RECENT_DAYS - 1).atStartOfDay(),
                today.plusDays(1).atStartOfDay());
        return result;
    }
}
