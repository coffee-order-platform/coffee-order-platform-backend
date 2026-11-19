package com.coffeeorder.domain.menu.dto;

import com.coffeeorder.domain.menu.entity.Menu;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** 인기 메뉴 한 줄: 순위(1부터), 메뉴, 최근 7일 판매 수량. 11/19 경화님 결정. */
@JsonPropertyOrder({"rank", "menuId", "name", "price", "salesCount"})
public record PopularMenuResponse(int rank, Long menuId, String name, long price, long salesCount) {

    public static PopularMenuResponse of(int rank, Menu menu, long salesCount) {
        return new PopularMenuResponse(rank, menu.getId(), menu.getName(), menu.getPrice(), salesCount);
    }
}
