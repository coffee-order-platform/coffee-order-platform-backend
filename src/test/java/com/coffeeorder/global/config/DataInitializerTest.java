package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataInitializerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PointRepository pointRepository = mock(PointRepository.class);
    private final MenuRepository menuRepository = mock(MenuRepository.class);
    private final DataInitializer initializer = new DataInitializer(userRepository, pointRepository, menuRepository);

    @Test
    void 두_테이블이_비어_있으면_사용자_3명_포인트_메뉴_5종을_만든다() {
        when(userRepository.count()).thenReturn(0L);
        when(menuRepository.count()).thenReturn(0L);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        initializer.run(null);

        ArgumentCaptor<Point> points = ArgumentCaptor.forClass(Point.class);
        verify(pointRepository, times(3)).save(points.capture());
        assertThat(points.getAllValues()).extracting(Point::getBalance).containsExactly(150_000L, 30_000L, 2_000L);
        assertThat(points.getAllValues()).extracting(p -> p.getUser().getName()).containsExactly("김커피", "이라떼", "박빈지갑");

        ArgumentCaptor<Menu> menus = ArgumentCaptor.forClass(Menu.class);
        verify(menuRepository, times(5)).save(menus.capture());
        assertThat(menus.getAllValues()).extracting(Menu::getName).containsExactly(
                "아메리카노", "폭신폭신 구름과자 라떼", "졸린 곰돌이 우유", "번개맞은 바닐라", "달빛 두리안 프라푸치노");
        assertThat(menus.getAllValues()).extracting(Menu::getPrice)
                .containsExactly(4_500L, 6_000L, 5_000L, 5_500L, 7_000L);
    }

    @Test
    void 사용자가_이미_있으면_사용자와_포인트는_만들지_않고_메뉴만_만든다() {
        when(userRepository.count()).thenReturn(1L);
        when(menuRepository.count()).thenReturn(0L);

        initializer.run(null);

        verify(userRepository, never()).save(any());
        verify(pointRepository, never()).save(any());
        verify(menuRepository, times(5)).save(any(Menu.class));
    }

    @Test
    void 메뉴만_남아_있고_사용자가_없으면_사용자와_포인트는_만들고_메뉴는_만들지_않는다() {
        when(userRepository.count()).thenReturn(0L);
        when(menuRepository.count()).thenReturn(1L);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        initializer.run(null);

        verify(pointRepository, times(3)).save(any(Point.class));
        verify(menuRepository, never()).save(any());
    }

    @Test
    void 메뉴가_이미_있으면_메뉴는_만들지_않는다() {
        when(userRepository.count()).thenReturn(1L);
        when(menuRepository.count()).thenReturn(1L);

        initializer.run(null);

        verify(menuRepository, never()).save(any());
    }
}
