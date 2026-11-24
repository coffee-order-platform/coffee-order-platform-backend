package com.coffeeorder.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeeorder.concurrency.ConcurrentRunner.Outcome;
import com.coffeeorder.domain.cart.service.CartRetryExecutor;
import com.coffeeorder.domain.cart.service.CartService;
import com.coffeeorder.domain.order.service.OrderService;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.point.service.PointService;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 노션 테스트 케이스 5장(동시성) X-1~X-6, R-8 을 실제 서비스 코드(주문·환불·충전·장바구니)에 진짜 동시 요청으로 확인한다.
 * 락은 커밋돼야 의미가 있어 롤백형(@Transactional) 테스트가 아니다 → 테스트 전용 스키마(coffee_order_test)에 커밋하고,
 * 테스트마다 만든 사용자와 그 데이터를 끝나고 지운다. 스레드 순서는 매번 달라서 한 번 통과는 우연일 수 있으므로 20번 반복한다.
 * 모든 실패는 "의도한 실패"(정해진 에러코드)여야 하고, 그 밖의 예외(교착·타임아웃·500)가 하나라도 나오면 사고로 실패한다.
 */
@SpringBootTest
class ConcurrencyRegressionTest {

    private static final int REPEAT = 20;
    private static final String NAME = "{displayName} {currentRepetition}/{totalRepetitions}"; // 실패 보고에 시나리오 이름이 나오게
    private static final int THREADS = 10;
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE; // PopularMenuRepository 와 같은 키 형식

    @Autowired OrderService orderService;
    @Autowired PointService pointService;
    @Autowired CartService cartService;
    @Autowired CartRetryExecutor cartRetryExecutor;
    @Autowired UserRepository userRepository;
    @Autowired PointRepository pointRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired Clock clock;
    @Autowired DataSource dataSource;

    private final List<Long> createdUsers = new ArrayList<>();

    @AfterEach
    void 만든_데이터_지우기() {
        for (Long userId : createdUsers) {
            jdbc.update("DELETE FROM point_histories WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM orders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM cart_items WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM points WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUsers.clear();
        // 주문 커밋 후 처리가 오늘 날짜 키(Redis 1번 DB)에 판매 수량을 더하므로 같이 지운다
        redis.delete("popular:menu:" + LocalDate.now(clock).format(KEY_DATE));
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X1_잔액_10000에_메뉴_5종_담기와_주문이_동시에_오면_잔액은_음수가_되지_않고_사용_내역과_맞는다() throws Exception {
        long userId = createUser(10_000);
        List<Long> menus = jdbc.queryForList("SELECT id FROM menus ORDER BY id", Long.class);
        long allPrice = count("SELECT SUM(price) FROM menus");
        assertThat(allPrice).as("메뉴 전부의 값이 잔액보다 커야 잔액 부족이 반드시 생긴다").isGreaterThan(10_000L);
        AtomicInteger turn = new AtomicInteger();

        // 스레드마다 서로 다른 메뉴 1잔을 담고 바로 주문한다(같은 메뉴면 담기끼리 충돌해 주문까지 못 간다 — 2라운드 L5).
        // 주문은 장바구니 전체를 결제해 남이 담은 잔도 같이 주문한다. 자기 잔은 자기 주문이 보거나 앞 주문이 가져가므로,
        // 모두 성공하면 전부 결제된 셈 → 잔액보다 비싸 잔액 부족이 반드시 1건 이상 생긴다.
        // 실측(11/24, 로컬 수백 회): 주문의 장바구니 FOR UPDATE 가 커밋 전 담기를 기다려 첫 주문도 5잔 전부를 보므로 성공은 거의 항상 0.
        // 그래서 이 테스트가 지키는 것은 "잔액 확인"(빠지면 음수)과 "전부 거절돼도 잔액·내역·장바구니가 그대로"다.
        // 포인트 락이 빠지는 회귀는 X-4·R-8 이 잡는다. 성공 개수는 환경에 따라 달라질 수 있어 개수 대신 불변식으로 본다
        List<Outcome> outcomes = ConcurrentRunner.run(menus.size(), () -> {
            long menuId = menus.get(turn.getAndIncrement());
            run(() -> cartService.add(userId, menuId, 1));
            return orderService.order(userId);
        }).outcomes();

        assertOnlyIntendedFailures(outcomes, Set.of(ErrorCode.CART_EMPTY, ErrorCode.INSUFFICIENT_POINT)); // 담기 실패는 사고
        assertThat(outcomes).as("잔액 부족 거절").anySatisfy(o -> assertThat(o.error()).isInstanceOfSatisfying(
                BusinessException.class, b -> assertThat(b.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT)));
        long successes = outcomes.stream().filter(Outcome::success).count();
        long used = count("SELECT COALESCE(SUM(amount), 0) FROM point_histories WHERE user_id = ? AND type = 'USE'", userId);
        assertThat(balance(userId)).isNotNegative().isEqualTo(10_000L - used); // 잔액 확인이 빠지면 음수가 된다
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = ?", userId)).isEqualTo(successes);
        assertThat(historyCount(userId, "USE")).isEqualTo(successes);
        assertThat(used).isEqualTo(count("SELECT COALESCE(SUM(oi.line_price), 0) FROM order_items oi "
                + "JOIN orders o ON o.id = oi.order_id WHERE o.user_id = ?", userId));
        // 잔 수 보존: 주문된 잔 + 장바구니에 남은 잔 = 담은 잔(메뉴 5종 1잔씩). 사라지거나 두 번 주문되거나 수량이 늘어난 잔이 없다
        // (행 개수가 아니라 수량 합계로 센다 — 한 행의 수량이 잘못 늘어도 잡히게, CodeRabbit PR #16)
        long ordered = count("SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.user_id = ?", userId);
        long left = count("SELECT COALESCE(SUM(quantity), 0) FROM cart_items WHERE user_id = ?", userId);
        assertThat(ordered + left).isEqualTo(menus.size());
        assertBalanceMatchesLastHistory(userId);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X2_같은_장바구니로_주문_10건이_동시에_오면_1건만_성공하고_한_번만_차감() throws Exception {
        long userId = createUser(100_000);
        cartService.add(userId, menuId("아메리카노"), 2); // 9,000원

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, () -> orderService.order(userId)).outcomes();

        assertCounts(outcomes, 1, Map.of(ErrorCode.CART_EMPTY, 9L));
        assertThat(balance(userId)).isEqualTo(91_000L);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = ?", userId)).isEqualTo(1);
        assertThat(historyCount(userId, "USE")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", userId)).isZero();
        assertBalanceMatchesLastHistory(userId);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X3_1000원_충전_10건이_동시에_오면_모두_반영돼_잔액이_10000_늘고_내역_10건() throws Exception {
        long userId = createUser(5_000);

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, () -> pointService.charge(userId, 1_000)).outcomes();

        assertCounts(outcomes, THREADS, Map.of());
        assertThat(balance(userId)).isEqualTo(15_000L);
        assertThat(historyCount(userId, "CHARGE")).isEqualTo(THREADS);
        assertBalanceMatchesLastHistory(userId);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X4_충전과_주문이_동시에_오면_최종_잔액은_초기_더하기_충전_빼기_주문() throws Exception {
        long userId = createUser(10_000);
        cartService.add(userId, menuId("아메리카노"), 1); // 4,500원
        AtomicInteger turn = new AtomicInteger();

        // 스레드 2개 중 하나는 충전, 하나는 주문. 어느 쪽이 먼저 락을 잡든 결과가 같아야 한다
        List<Outcome> outcomes = ConcurrentRunner.run(2, () -> turn.getAndIncrement() == 0
                ? pointService.charge(userId, 5_000)
                : orderService.order(userId)).outcomes();

        assertCounts(outcomes, 2, Map.of());
        assertThat(balance(userId)).isEqualTo(10_000L + 5_000 - 4_500);
        assertThat(historyCount(userId, "CHARGE")).isEqualTo(1);
        assertThat(historyCount(userId, "USE")).isEqualTo(1);
        assertBalanceMatchesLastHistory(userId);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X5_같은_메뉴를_동시에_처음_담으면_행_1개에_수량이_합쳐진다() throws Exception {
        long userId = createUser(0);
        long americano = menuId("아메리카노");

        // 컨트롤러와 같은 경로: 재시도기가 시도마다 새 트랜잭션으로 서비스를 부른다(UNIQUE 위반은 1회 재시도)
        List<Outcome> outcomes = ConcurrentRunner.run(2,
                () -> run(() -> cartService.add(userId, americano, 1))).outcomes();

        assertCounts(outcomes, 2, Map.of());
        assertThat(jdbc.queryForList("SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?",
                Integer.class, userId, americano)).containsExactly(2);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X5_삭제_직후_같은_메뉴를_동시에_담아_교착이_나도_재시도로_행_1개에_합쳐진다() throws Exception {
        long userId = createUser(0);
        long americano = menuId("아메리카노");
        cartService.add(userId, americano, 1);

        // X-1 에서 실제로 난 교착(11/24, InnoDB 교착 기록)을 재현한다. 매번은 아니고 로컬 기준 20회 중 약 8회 난다
        // (재시도가 없으면 20회 안에 거의 확실히 실패한다):
        // 1) 지운 행은 바로 없어지지 않고 "지움 표시"로 남는다. 백그라운드 정리(purge)가 먼저 치우지 않게 읽기 시점을 연 연결을 붙잡는다
        // 2) 그 연결이 주문처럼 이 사용자의 장바구니 범위를 FOR UPDATE 로 잠근다 → 두 담기의 INSERT 가 중복 확인용 공유 락을 기다리며 줄을 선다
        // 3) 잠금을 풀면 두 INSERT 가 동시에 공유 락을 받고, 서로의 공유 락 때문에 쓰기를 못 해 교착 → MySQL 이 한쪽을 되돌린다
        List<Outcome> outcomes;
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            held.createStatement().execute("SELECT COUNT(*) FROM cart_items"); // 1) 읽기 시점
            run(() -> cartService.remove(userId, americano));
            held.createStatement().execute("SELECT id FROM cart_items WHERE user_id = " + userId + " FOR UPDATE"); // 2)

            CompletableFuture<List<Outcome>> adds = CompletableFuture.supplyAsync(() -> {
                try {
                    return ConcurrentRunner.run(2, () -> run(() -> cartService.add(userId, americano, 1))).outcomes();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(500); // 두 담기가 줄을 설 시간. 모자라면 교착이 안 날 뿐 결과 단언은 같다(거짓 실패는 없다)
            held.commit(); // 3)
            // ConcurrentRunner 의 작업 대기(60초) + 종료 대기(60초)보다 길게 기다린다. 더 짧으면 담기가 아직 도는 중에 정리가 시작될 수 있다
            outcomes = adds.get(150, TimeUnit.SECONDS);
        }

        assertCounts(outcomes, 2, Map.of()); // 교착으로 진 쪽은 통째로 취소되고, 다시 하면 이긴 쪽 행에 수량을 더한다
        assertThat(jdbc.queryForList("SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?",
                Integer.class, userId, americano)).containsExactly(2);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X6_같은_장바구니_행을_동시에_수정하면_재시도로_둘_다_반영되고_마지막_값이_남는다() throws Exception {
        long userId = createUser(0);
        long americano = menuId("아메리카노");
        cartService.add(userId, americano, 1);
        AtomicInteger turn = new AtomicInteger();

        // 서로 다른 값으로 바꿔야 실제 UPDATE 가 나가 버전 충돌이 생긴다(같은 값이면 변경 감지가 UPDATE 를 생략한다)
        List<Outcome> outcomes = ConcurrentRunner.run(2, () -> {
            int quantity = turn.getAndIncrement() == 0 ? 3 : 7;
            return run(() -> cartService.changeQuantity(userId, americano, quantity));
        }).outcomes();

        // 2건이면 충돌은 많아야 1번이라 2회 재시도 안에 끝난다 → 409 없이 둘 다 성공. 3번째 충돌의 409 는 CartRetryExecutorTest 가 본다
        assertCounts(outcomes, 2, Map.of());
        assertThat(jdbc.queryForList("SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?",
                Integer.class, userId, americano)).singleElement().isIn(3, 7);
        // 처음 담기(version 0) 뒤 두 번 모두 UPDATE 됐다 = 어느 쪽도 덮어쓰기로 사라지지 않았다
        assertThat(count("SELECT version FROM cart_items WHERE user_id = ? AND menu_id = ?", userId, americano))
                .isEqualTo(2);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void X6_같은_행을_5건이_동시에_수정하면_성공_아니면_409이고_성공한_만큼만_버전이_오른다() throws Exception {
        long userId = createUser(0);
        long americano = menuId("아메리카노");
        cartService.add(userId, americano, 1);
        AtomicInteger turn = new AtomicInteger();

        // 5건이면 충돌이 재시도 2회를 넘어 409 가 날 수 있다. 몇 건이 409 인지는 매번 달라서 불변식으로 본다.
        // 값은 2~6 으로 모두 달라야 한다(처음 값 1 이나 서로 같은 값이면 UPDATE 가 생략돼 버전이 안 오른다)
        List<Outcome> outcomes = ConcurrentRunner.run(5, () -> {
            int quantity = 2 + turn.getAndIncrement();
            return run(() -> cartService.changeQuantity(userId, americano, quantity));
        }).outcomes();

        assertOnlyIntendedFailures(outcomes, Set.of(ErrorCode.CART_UPDATE_CONFLICT));
        long successes = outcomes.stream().filter(Outcome::success).count();
        assertThat(successes).isPositive(); // 처음 커밋한 1건은 충돌할 상대가 없다
        assertThat(count("SELECT version FROM cart_items WHERE user_id = ? AND menu_id = ?", userId, americano))
                .isEqualTo(successes); // 409 가 된 요청은 아무것도 바꾸지 않았다
        assertThat(jdbc.queryForList("SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?",
                Integer.class, userId, americano)).singleElement().isIn(2, 3, 4, 5, 6);
    }

    @RepeatedTest(value = REPEAT, name = NAME)
    void R8_같은_주문_환불_10건이_동시에_오면_1건만_성공하고_잔액은_한_번만_복구() throws Exception {
        long userId = createUser(20_000);
        cartService.add(userId, menuId("아메리카노"), 2); // 9,000원
        long orderId = orderService.order(userId).orderId();

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, () -> orderService.refund(userId, orderId)).outcomes();

        assertCounts(outcomes, 1, Map.of(ErrorCode.ALREADY_REFUNDED, 9L));
        assertThat(balance(userId)).isEqualTo(20_000L);
        assertThat(historyCount(userId, "REFUND")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("REFUNDED");
        assertBalanceMatchesLastHistory(userId);
    }

    // ---- 도우미 ----

    private Object run(Runnable attempt) {
        cartRetryExecutor.run(attempt);
        return null;
    }

    /** 성공 수와 의도한 실패(에러코드별 수)를 확인한다. 정해진 에러코드가 아닌 예외는 모두 사고로 보고 원인을 그대로 보여 준다. */
    private static void assertCounts(List<Outcome> outcomes, long successes, Map<ErrorCode, Long> intended) {
        List<Throwable> accidents = outcomes.stream().map(Outcome::error)
                .filter(e -> e != null && !(e instanceof BusinessException)).toList();
        assertThat(accidents).as("사고(의도하지 않은 예외)").isEmpty();
        assertThat(outcomes.stream().filter(Outcome::success).count()).as("성공 수").isEqualTo(successes);
        assertThat(outcomes.stream().map(Outcome::error).filter(e -> e != null)
                .collect(Collectors.groupingBy(e -> ((BusinessException) e).getErrorCode(), Collectors.counting())))
                .as("의도한 실패").isEqualTo(intended);
    }

    /** 개수가 매번 달라지는 시나리오용: 실패는 모두 허용된 에러코드여야 하고, 그 밖의 예외는 사고다. */
    private static void assertOnlyIntendedFailures(List<Outcome> outcomes, Set<ErrorCode> allowed) {
        assertThat(outcomes.stream().map(Outcome::error).filter(e -> e != null).toList())
                .as("사고(허용된 에러코드가 아닌 실패)")
                .allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(BusinessException.class,
                        b -> assertThat(b.getErrorCode()).isIn(allowed)));
    }

    private long createUser(long balance) {
        Long id = tx.execute(status -> {
            User user = userRepository.save(new User("동시성테스터"));
            pointRepository.save(new Point(user, balance));
            return user.getId();
        });
        createdUsers.add(id);
        return id;
    }

    private long menuId(String name) {
        return jdbc.queryForObject("SELECT id FROM menus WHERE name = ?", Long.class, name);
    }

    private long balance(long userId) {
        return jdbc.queryForObject("SELECT balance FROM points WHERE user_id = ?", Long.class, userId);
    }

    private long historyCount(long userId, String type) {
        return count("SELECT COUNT(*) FROM point_histories WHERE user_id = ? AND type = ?", userId, type);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 노션 H-3: 마지막 내역의 처리 후 잔액 = 현재 잔액 (끼어든 요청이 내역과 잔액을 어긋나게 하지 않았다). 내역이 없으면 볼 것이 없다(X-1 의 전부 거절) */
    private void assertBalanceMatchesLastHistory(long userId) {
        assertThat(jdbc.queryForList(
                "SELECT balance_after FROM point_histories WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, userId)).allSatisfy(last -> assertThat(last).isEqualTo(balance(userId)));
    }
}
