package com.coffeeorder.domain.menu.controller;

import com.coffeeorder.domain.menu.dto.PopularMenuResponse;
import com.coffeeorder.domain.menu.service.PopularMenuDbService;
import com.coffeeorder.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 실험용 인기 메뉴 DB 집계 API(EXP-4). 평소에는 꺼져 있어 404 다. 켜기: --experiment.popular-db.enabled=true */
@RestController
@RequestMapping("/api/menus")
@ConditionalOnProperty(name = "experiment.popular-db.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PopularMenuDbController {

    private final PopularMenuDbService popularMenuDbService;

    @GetMapping("/popular/db")
    public ApiResponse<List<PopularMenuResponse>> findPopularFromDb() {
        return ApiResponse.ok(popularMenuDbService.getTop3());
    }
}
