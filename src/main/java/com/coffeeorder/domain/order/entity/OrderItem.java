package com.coffeeorder.domain.order.entity;

import com.coffeeorder.domain.menu.entity.Menu;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문 항목 스냅샷. 주문 당시 메뉴 이름·가격을 복사해 둔다(메뉴가 바뀌어도 주문 내역은 그대로). */
@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(name = "menu_name", nullable = false, length = 100)
    private String menuName;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_price", nullable = false)
    private long linePrice;

    public OrderItem(Order order, Menu menu, String menuName, long unitPrice, int quantity, long linePrice) {
        this.order = order;
        this.menu = menu;
        this.menuName = menuName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.linePrice = linePrice;
    }
}
