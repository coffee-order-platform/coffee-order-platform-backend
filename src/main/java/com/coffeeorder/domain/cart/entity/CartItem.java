package com.coffeeorder.domain.cart.entity;

import com.coffeeorder.domain.menu.entity.Menu;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 장바구니 항목. 같은 (사용자, 메뉴)는 한 행(UNIQUE)이고, 동시 수정은 @Version 으로 막는다. */
@Getter
@Entity
@Table(
        name = "cart_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_items_user_id_menu_id", columnNames = {"user_id", "menu_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(nullable = false)
    private int quantity;

    @Version
    @Column(nullable = false)
    private Long version;

    public CartItem(User user, Menu menu, int quantity) {
        this.user = user;
        this.menu = menu;
        this.quantity = quantity;
    }

    /** 범위(1~99) 검증은 서비스가 한다. 저장 시 version 이 바뀌었으면 낙관적 락 충돌이 난다. */
    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }
}
