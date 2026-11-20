package com.coffeeorder.domain.menu.controller;

import com.coffeeorder.domain.menu.dto.MenuResponse;
import com.coffeeorder.domain.menu.dto.PopularMenuResponse;
import com.coffeeorder.domain.menu.service.MenuService;
import com.coffeeorder.domain.menu.service.PopularMenuService;
import com.coffeeorder.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;
    private final PopularMenuService popularMenuService;

    @GetMapping
    public ApiResponse<List<MenuResponse>> findAll() {
        return ApiResponse.ok(menuService.findAll());
    }

    @GetMapping("/popular")
    public ApiResponse<List<PopularMenuResponse>> findPopular() {
        return ApiResponse.ok(popularMenuService.getTop3());
    }
}
