package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.dto.PopularMenuResponse;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PopularMenuService {

    static final int RECENT_DAYS = 7; // 오늘 포함 7일, 서울 날짜(노션 11/17 결정)
    static final int TOP = 3;

    private final PopularMenuRepository popularMenuRepository;
    private final MenuRepository menuRepository;
    private final Clock clock;

    /**
     * 최근 7일 판매 수량 합계 상위 3개. 동점이면 메뉴 ID 가 작은 순, 판매가 3개 미만이면 있는 만큼(노션 5-4).
     * 환불로 합계가 0 이 된 메뉴는 팔린 것이 아니므로 뺀다. Redis 장애는 그대로 던져 500 이 된다(이번 주 결정).
     * 트랜잭션을 걸지 않는다 — Redis 를 읽는 동안 DB 연결을 잡고 있지 않게(메뉴 조회는 저장소가 자체 트랜잭션으로 한다).
     */
    public List<PopularMenuResponse> getTop3() {
        List<Map.Entry<Long, Long>> ranked = popularMenuRepository.sumRecent(LocalDate.now(clock), RECENT_DAYS)
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .toList();
        Map<Long, Menu> menus = menuRepository.findAllById(ranked.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        List<PopularMenuResponse> result = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : ranked) {
            Menu menu = menus.get(entry.getKey());
            if (menu != null) { // 메뉴 삭제 API 는 없지만, Redis 에 남은 옛 ID 가 응답을 깨지 않게 한다
                result.add(PopularMenuResponse.of(result.size() + 1, menu, entry.getValue()));
            }
        }
        return result;
    }
}
