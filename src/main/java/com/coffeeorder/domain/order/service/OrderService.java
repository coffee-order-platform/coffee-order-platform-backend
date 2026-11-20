package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.cart.entity.CartItem;
import com.coffeeorder.domain.cart.repository.CartItemRepository;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.order.dto.OrderDetailResponse;
import com.coffeeorder.domain.order.dto.OrderResponse;
import com.coffeeorder.domain.order.dto.OrderSummaryResponse;
import com.coffeeorder.domain.order.dto.RefundResponse;
import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.entity.OrderItem;
import com.coffeeorder.domain.order.entity.OrderStatus;
import com.coffeeorder.domain.order.event.OrderCompletedEvent;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import com.coffeeorder.domain.order.event.OrderRefundedEvent;
import com.coffeeorder.domain.order.repository.OrderItemRepository;
import com.coffeeorder.domain.order.repository.OrderRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.global.common.CursorResponse;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final UserRepository userRepository;
    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final CartItemRepository cartItemRepository;
    private final MenuRepository menuRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 장바구니 전체를 포인트로 결제한다(노션 5-3). 한 트랜잭션이라 중간에 실패하면 포인트·주문·장바구니 모두 롤백된다.
     * 포인트 락을 먼저 잡아, 같은 사용자의 동시 주문은 줄을 서고 두 번째는 빈 장바구니(CART_EMPTY)를 본다.
     * 사용 내역이 주문을 가리키므로 주문을 먼저 저장한다(노션 4·5단계 순서와 달라도 한 트랜잭션이라 결과는 같다).
     */
    @Transactional
    public OrderResponse order(Long userId) {
        // 락 조회를 맨 처음에 한다. 일반 조회(사용자 확인 등)를 먼저 하면 MySQL REPEATABLE READ 의 읽기 시점이
        // 락을 잡기 전으로 고정돼, 뒤의 메뉴 조회가 락 대기 중 바뀐 가격을 보지 못한다(CodeRabbit PR #8).
        // Point 를 처음 읽는 곳이기도 해야 한다(먼저 일반 조회하면 락 조회가 메모리의 옛 잔액을 돌려준다)
        Point point = lockPoint(userId);

        List<CartItem> cartItems = cartItemRepository.findAllByUserIdForUpdate(userId);
        if (cartItems.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }
        List<Long> menuIds = cartItems.stream().map(item -> item.getMenu().getId()).toList();
        Map<Long, Menu> menus = menuRepository.findAllById(menuIds).stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        // 가격은 장바구니가 아니라 주문 시점의 메뉴 가격이다
        long totalPrice = cartItems.stream()
                .mapToLong(item -> menus.get(item.getMenu().getId()).getPrice() * item.getQuantity())
                .sum();
        if (point.getBalance() < totalPrice) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }

        Order order = orderRepository.save(new Order(point.getUser(), totalPrice));
        List<OrderItem> orderItems = orderItemRepository.saveAll(cartItems.stream().map(item -> {
            Menu menu = menus.get(item.getMenu().getId());
            return new OrderItem(order, menu, menu.getName(), menu.getPrice(), item.getQuantity(),
                    menu.getPrice() * item.getQuantity());
        }).toList());
        point.use(totalPrice);
        pointHistoryRepository.save(
                new PointHistory(point.getUser(), order, PointHistoryType.USE, totalPrice, point.getBalance()));
        cartItemRepository.deleteAll(cartItems); // 주문할 때 읽은 항목만 지운다

        eventPublisher.publishEvent(new OrderCompletedEvent(order.getId(), userId,
                cartItems.stream().map(item -> new OrderedMenu(item.getMenu().getId(), item.getQuantity())).toList(),
                totalPrice, order.getCreatedAt()));
        return new OrderResponse(order.getId(), totalPrice, point.getBalance(), order.getCreatedAt(),
                orderItems.stream().map(OrderResponse.Item::from).toList());
    }

    /** 최신순 커서 조회(포인트 내역과 같은 규칙). size+1 개를 읽어 다음 페이지 여부를 판단한다. 커서는 이 사용자의 주문 id 여야 한다. */
    @Transactional(readOnly = true)
    public CursorResponse<OrderSummaryResponse> getOrders(Long userId, Long cursor, int size) {
        validateUser(userId);
        Limit limit = Limit.of(size + 1);
        List<Order> fetched;
        if (cursor == null) {
            fetched = orderRepository.findByUserIdOrderByIdDesc(userId, limit);
        } else {
            if (orderRepository.findByIdAndUserId(cursor, userId).isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            fetched = orderRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, limit);
        }
        return CursorResponse.of(
                fetched.stream().map(OrderSummaryResponse::from).toList(), size, OrderSummaryResponse::orderId);
    }

    /** 없는 주문과 남의 주문은 똑같이 ORDER_NOT_FOUND — 남의 주문이 있는지 드러내지 않는다. */
    @Transactional(readOnly = true)
    public OrderDetailResponse getOrder(Long userId, Long orderId) {
        validateUser(userId);
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResponse.of(order, orderItemRepository.findByOrderIdOrderById(orderId));
    }

    /**
     * 전체 환불(노션 5-5). 한 트랜잭션이라 중간에 실패하면 잔액·내역·주문 상태 모두 롤백된다.
     * 순서: 포인트 락 → 주문 조회(소유자 조건) → 상태 확인 → 잔액 복구·환불 내역 → 상태 REFUNDED → 커밋 후 인기 메뉴 차감.
     * - 소유자 확인을 상태 확인보다 먼저 한다. 반대면 남의 환불된 주문에 ALREADY_REFUNDED 가 나가 그 주문이 있다는 게 드러난다.
     * - 같은 주문 동시 환불: 주문은 그 사용자 것이므로 포인트 락으로 줄을 선다. 주문 조회가 락을 잡은 뒤의 첫 일반 조회라,
     *   두 번째 요청은 앞 요청이 커밋한 REFUNDED 를 보고 ALREADY_REFUNDED 가 된다(REPEATABLE READ 읽기 시점, 주문 API 와 같은 이유).
     */
    @Transactional
    public RefundResponse refund(Long userId, Long orderId) {
        Point point = lockPoint(userId);
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException(ErrorCode.ALREADY_REFUNDED);
        }

        long amount = order.getTotalPrice();
        point.refund(amount);
        pointHistoryRepository.save(
                new PointHistory(point.getUser(), order, PointHistoryType.REFUND, amount, point.getBalance()));
        order.refund();

        eventPublisher.publishEvent(new OrderRefundedEvent(orderId,
                orderItemRepository.findByOrderIdOrderById(orderId).stream()
                        .map(item -> new OrderedMenu(item.getMenu().getId(), item.getQuantity())).toList(),
                order.getCreatedAt()));
        return new RefundResponse(orderId, amount, point.getBalance());
    }

    /**
     * 포인트 행을 비관적 락으로 잡는다. 주문·환불 모두 트랜잭션의 첫 조회여야 한다 — 일반 조회를 먼저 하면
     * MySQL REPEATABLE READ 읽기 시점이 락을 잡기 전으로 고정되고(CodeRabbit PR #8), 락 조회가 메모리의 옛 잔액을 돌려준다.
     * 행이 없을 때만 사용자 존재를 확인해 USER_NOT_FOUND 를 가린다.
     */
    private Point lockPoint(Long userId) {
        Optional<Point> lockedPoint = pointRepository.findByUserIdForUpdate(userId);
        if (lockedPoint.isPresent()) {
            return lockedPoint.get();
        }
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        throw new IllegalStateException("포인트 행이 없는 사용자: " + userId);
    }

    private void validateUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
