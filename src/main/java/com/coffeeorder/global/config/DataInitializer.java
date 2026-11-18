package com.coffeeorder.global.config;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 시작 시 가짜 사용자·메뉴를 만든다. 테이블마다 비어 있을 때만 만들어 재시작해도 중복되지 않는다.
 * 사용자와 포인트 행은 한 트랜잭션이라 "포인트 없는 사용자"가 남지 않는다. 초기 포인트는 충전 내역에 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private record SeedUser(String name, long point) {}

    private record SeedMenu(String name, long price) {}

    private static final List<SeedUser> USERS = List.of(
            new SeedUser("김커피", 150_000),
            new SeedUser("이라떼", 30_000),
            new SeedUser("박빈지갑", 2_000));

    private static final List<SeedMenu> MENUS = List.of(
            new SeedMenu("아메리카노", 4_500),
            new SeedMenu("폭신폭신 구름과자 라떼", 6_000),
            new SeedMenu("졸린 곰돌이 우유", 5_000),
            new SeedMenu("번개맞은 바닐라", 5_500),
            new SeedMenu("달빛 두리안 프라푸치노", 7_000));

    private final UserRepository userRepository;
    private final PointRepository pointRepository;
    private final MenuRepository menuRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            for (SeedUser seed : USERS) {
                User user = userRepository.save(new User(seed.name()));
                pointRepository.save(new Point(user, seed.point()));
            }
            log.info("가짜 사용자 {}명 생성", USERS.size());
        }
        if (menuRepository.count() == 0) {
            MENUS.forEach(seed -> menuRepository.save(new Menu(seed.name(), seed.price())));
            log.info("가짜 메뉴 {}종 생성", MENUS.size());
        }
    }
}
