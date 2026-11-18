package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DataInitializerIntegrationTest {

    @Autowired DataInitializer initializer;
    @Autowired UserRepository userRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired JdbcTemplate jdbc;

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
    void 시드_사용자의_초기_포인트가_DB_원본에_맞게_들어가_있다() {
        // 전역 건수가 아니라 시드 사용자 이름으로만 확인한다(다른 테스트가 커밋한 데이터와 간섭하지 않게)
        assertThat(balanceOf("김커피")).isEqualTo(150_000L);
        assertThat(balanceOf("이라떼")).isEqualTo(30_000L);
        assertThat(balanceOf("박빈지갑")).isEqualTo(2_000L);
    }

    @Test
    void 시드_사용자의_충전_내역은_없다() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_histories h JOIN users u ON u.id = h.user_id"
                        + " WHERE u.name IN ('김커피', '이라떼', '박빈지갑')",
                Long.class);

        assertThat(count).isZero();
    }

    @Test
    void run_에는_사용자와_포인트를_한_트랜잭션으로_묶는_애너테이션이_있다() throws Exception {
        // 지우면 포인트 저장이 실패할 때 포인트 없는 사용자가 남는다. 동작 검증은 빈 DB 가 필요해 애너테이션을 고정한다.
        assertThat(DataInitializer.class.getMethod("run", org.springframework.boot.ApplicationArguments.class)
                        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .isTrue();
    }

    private Long balanceOf(String userName) {
        return jdbc.queryForObject(
                "SELECT p.balance FROM points p JOIN users u ON u.id = p.user_id WHERE u.name = ? LIMIT 1",
                Long.class, userName);
    }
}
