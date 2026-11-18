package com.coffeeorder.domain.menu.dto;

import com.coffeeorder.domain.menu.entity.Menu;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"menuId", "name", "price"})
public record MenuResponse(Long menuId, String name, long price) {

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(menu.getId(), menu.getName(), menu.getPrice());
    }
}
