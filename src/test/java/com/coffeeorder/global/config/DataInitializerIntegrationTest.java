package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트마다 트랜잭션 안에서 테이블을 비운 뒤 시더를 직접 실행한다. 끝나면 롤백되므로
 * 테스트 전용 스키마(coffee_order_test)의 데이터는 그대로 남고, 이전 실행이나 다른 테스트가 남긴 데이터에도 흔들리지 않는다.
 */
@SpringBootTest
@Transactional
class DataInitializerIntegrationTest {

    @Autowired DataInitializer initializer;
    @Autowired UserRepository userRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void 빈_테이블로_시작한다() {
        // FK 순서대로 자식 테이블부터 지운다(트랜잭션 롤백으로 원상 복구됨)
        for (String table : new String[] {
                "point_histories", "order_items", "orders", "cart_items", "points", "users", "menus"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void 빈_DB에서_사용자_3명_포인트_메뉴_5종을_만들고_초기_포인트를_충전_내역으로_남긴다() {
        initializer.run(null);

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(menuRepository.count()).isEqualTo(5);
        assertThat(balanceOf("김커피")).isEqualTo(150_000L);
        assertThat(balanceOf("이라떼")).isEqualTo(30_000L);
        assertThat(balanceOf("박빈지갑")).isEqualTo(2_000L);
        // 사용자마다 CHARGE 1건, 잔액 = 내역 합계(노션 3, 11/19)
        assertThat(jdbc.queryForList("SELECT type, COUNT(*) AS cnt FROM point_histories GROUP BY type"))
                .containsExactly(Map.of("type", "CHARGE", "cnt", 3L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points p WHERE p.balance <> "
                + "(SELECT COALESCE(SUM(h.amount), 0) FROM point_histories h WHERE h.user_id = p.user_id)", Long.class))
                .isZero();
    }

    @Test
    void 두_번_실행해도_건수가_변하지_않는다() {
        initializer.run(null);
        initializer.run(null);

        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(menuRepository.count()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_histories", Long.class)).isEqualTo(3);
    }

    @Test
    void run_에는_사용자와_포인트를_한_트랜잭션으로_묶는_애너테이션이_있다() throws Exception {
        // 지우면 포인트 저장이 실패할 때 포인트 없는 사용자가 남는다.
        // 실제 롤백 확인(커밋형)은 테스트 전용 스키마가 생겨 이제 할 수 있지만 아직 하지 않아, 그때까지 애너테이션을 고정한다.
        assertThat(DataInitializer.class.getMethod("run", ApplicationArguments.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    private Long balanceOf(String userName) {
        return jdbc.queryForObject(
                "SELECT p.balance FROM points p JOIN users u ON u.id = p.user_id WHERE u.name = ?",
                Long.class, userName);
    }
}
