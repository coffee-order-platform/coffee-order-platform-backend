package com.coffeeorder.domain.cart.service;

import com.coffeeorder.domain.cart.dto.CartItemResponse;
import com.coffeeorder.domain.cart.dto.CartResponse;
import com.coffeeorder.domain.cart.entity.CartItem;
import com.coffeeorder.domain.cart.repository.CartItemRepository;
import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import com.coffeeorder.domain.user.repository.UserRepository;
import com.coffeeorder.global.exception.BusinessException;
import com.coffeeorder.global.exception.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 한 번의 시도(트랜잭션 1개). 충돌 재시도는 바깥의 {@link CartRetryExecutor} 가 새 트랜잭션으로 다시 부른다.
 * 낙관적 락 충돌은 커밋(flush) 때 나므로 메서드 안이 아니라 프록시 밖으로 던져진다.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 99;

    private final UserRepository userRepository;
    private final MenuRepository menuRepository;
    private final CartItemRepository cartItemRepository;

    /** 같은 메뉴가 있으면 수량을 더한다(합친 결과도 1~99). 없으면 새 행 — 동시 첫 담기는 UNIQUE 위반으로 드러난다. */
    @Transactional
    public void add(Long userId, Long menuId, int quantity) {
        validateUser(userId);
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
        validateQuantity(quantity);

        Optional<CartItem> existing = cartItemRepository.findByUserIdAndMenuId(userId, menuId);
        if (existing.isPresent()) {
            int merged = existing.get().getQuantity() + quantity;
            validateQuantity(merged);
            existing.get().changeQuantity(merged);
            return;
        }
        cartItemRepository.save(new CartItem(userRepository.getReferenceById(userId), menu, quantity));
    }

    @Transactional
    public void changeQuantity(Long userId, Long menuId, int quantity) {
        validateUser(userId);
        validateQuantity(quantity);
        findItem(userId, menuId).changeQuantity(quantity);
    }

    @Transactional
    public void remove(Long userId, Long menuId) {
        validateUser(userId);
        cartItemRepository.delete(findItem(userId, menuId));
    }

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        validateUser(userId);
        return CartResponse.of(cartItemRepository.findAllWithMenuByUserId(userId).stream()
                .map(CartItemResponse::from).toList());
    }

    private CartItem findItem(Long userId, Long menuId) {
        return cartItemRepository.findByUserIdAndMenuId(userId, menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
    }

    private void validateQuantity(int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
    }

    private void validateUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
