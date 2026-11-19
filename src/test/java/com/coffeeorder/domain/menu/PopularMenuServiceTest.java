package com.coffeeorder.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coffeeorder.domain.menu.dto.PopularMenuResponse;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.menu.repository.PopularMenuRepository;
import com.coffeeorder.domain.menu.service.PopularMenuService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 순위 규칙을 Redis·DB 없이 확인한다. 합계를 담는 Map 의 순서에 기대지 않도록,
 * 일부러 큰 메뉴 ID 를 먼저 넣은 LinkedHashMap 을 돌려준다(HashMap 은 작은 ID 가 우연히 오름차순으로 나온다).
 */
class PopularMenuServiceTest {

    private final PopularMenuRepository popularMenuRepository = mock(PopularMenuRepository.class);
    private final MenuRepository menuRepository = mock(MenuRepository.class);
    private final PopularMenuService service = new PopularMenuService(popularMenuRepository, menuRepository,
            Clock.fixed(Instant.parse("2099-03-10T03:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void 판매_수량이_같으면_메뉴_ID_가_작은_순이다() {
        givenSums(new LinkedHashMap<>(), 40L, 2L, 7L, 2L, 30L, 2L);
        givenMenus(7L, 30L, 40L);

        assertThat(service.getTop3()).extracting(PopularMenuResponse::menuId).containsExactly(7L, 30L, 40L);
    }

    @Test
    void 판매_수량이_많은_순이고_상위_3개까지다() {
        givenSums(new LinkedHashMap<>(), 1L, 1L, 2L, 5L, 3L, 3L, 4L, 4L);
        givenMenus(2L, 3L, 4L);

        assertThat(service.getTop3()).extracting(PopularMenuResponse::menuId, PopularMenuResponse::salesCount)
                .containsExactly(tuple(2L, 5L),
                        tuple(4L, 4L), tuple(3L, 3L));
    }

    @Test
    void 합계가_0_이하인_메뉴는_뺀다() {
        givenSums(new LinkedHashMap<>(), 1L, 0L, 2L, -1L, 3L, 1L);
        givenMenus(1L, 2L, 3L); // 메뉴는 모두 있어서, 빠지는 이유가 "DB 에 없음"이 아니라 합계 0 이하뿐이다

        assertThat(service.getTop3()).extracting(PopularMenuResponse::menuId).containsExactly(3L);
    }

    @Test
    void DB_에_없는_메뉴는_건너뛰고_순위는_1부터_이어진다() {
        // Redis 에만 남은 옛 메뉴 ID 가 1위여도 응답이 깨지지 않고, 나머지가 1위부터 붙는다
        givenSums(new LinkedHashMap<>(), 99L, 10L, 1L, 5L, 2L, 3L);
        givenMenus(1L, 2L);

        assertThat(service.getTop3()).extracting(PopularMenuResponse::rank, PopularMenuResponse::menuId)
                .containsExactly(tuple(1, 1L), tuple(2, 2L));
    }

    // ---- 도우미 ----

    /** (메뉴 ID, 합계) 쌍을 넣은 순서 그대로 돌려준다. */
    private void givenSums(LinkedHashMap<Long, Long> sums, long... idAndCount) {
        for (int i = 0; i < idAndCount.length; i += 2) {
            sums.put(idAndCount[i], idAndCount[i + 1]);
        }
        when(popularMenuRepository.sumRecent(any(), anyInt())).thenReturn(sums);
    }

    private void givenMenus(long... ids) {
        List<Menu> menus = new ArrayList<>();
        for (long id : ids) {
            Menu menu = new Menu("메뉴" + id, 1_000L * id);
            ReflectionTestUtils.setField(menu, "id", id);
            menus.add(menu);
        }
        when(menuRepository.findAllById(any())).thenReturn(menus);
    }
}
