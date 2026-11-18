package com.coffeeorder.domain.menu.service;

import com.coffeeorder.domain.menu.dto.MenuResponse;
import com.coffeeorder.domain.menu.repository.MenuRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public List<MenuResponse> findAll() {
        return menuRepository.findAll(Sort.by("id")).stream().map(MenuResponse::from).toList();
    }
}
