package com.coffeeorder.domain.order.service;

import com.coffeeorder.domain.cart.entity.CartItem;
import com.coffeeorder.domain.cart.repository.CartItemRepository;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.order.dto.OrderResponse;
import com.coffeeorder.domain.order.entity.Order;
import com.coffeeorder.domain.order.entity.OrderItem;
import com.coffeeorder.domain.order.event.OrderCompletedEvent;
import com.coffeeorder.domain.order.event.OrderCompletedEvent.OrderedMenu;
import com.coffeeorder.domain.order.repository.OrderItemRepository;
import com.coffeeorder.domain.order.repository.OrderRepository;
import com.coffeeorder.domain.point.entity.Point;
import com.coffeeorder.domain.point.entity.PointHistory;
import com.coffeeorder.domain.point.entity.PointHistoryType;
import com.coffeeorder.domain.point.repository.PointHistoryRepository;
import com.coffeeorder.domain.point.repository.PointRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
        Optional<Point> lockedPoint = pointRepository.findByUserIdForUpdate(userId);
        if (lockedPoint.isEmpty()) {
            if (!userRepository.existsById(userId)) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            }
            throw new IllegalStateException("포인트 행이 없는 사용자: " + userId);
        }
        Point point = lockedPoint.get();

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
}
