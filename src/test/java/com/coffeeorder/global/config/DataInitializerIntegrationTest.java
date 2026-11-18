package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DataInitializerIntegrationTest {

    @Autowired DataInitializer initializer;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired PointHistoryRepository pointHistoryRepository;

    @Test
    void 기동하면_시드가_들어가고_다시_실행해도_건수가_변하지_않는다() {
        long users = userRepository.count();
        long menus = menuRepository.count();
        assertThat(users).isGreaterThanOrEqualTo(3);
        assertThat(menus).isGreaterThanOrEqualTo(5);

        initializer.run(null);

        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(menuRepository.count()).isEqualTo(menus);
    }

    @Test
    void 사용자마다_포인트_행이_있고_초기_포인트는_충전_내역에_남지_않는다() {
        assertThat(pointRepository.count()).isEqualTo(userRepository.count());
        assertThat(pointHistoryRepository.count()).isZero();
    }
}
