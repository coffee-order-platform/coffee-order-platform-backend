package com.coffeeorder.domain.cart;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coffeeorder.domain.cart.service.CartService;
import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨트롤러가 재시도기를 거쳐 서비스를 부르는지, 충돌이 계속되면 HTTP 409 가 나가는지 확인한다.
 * 서비스가 충돌 예외를 던지게 흉내 내므로 커밋이 필요 없다(실제 스레드 경합은 D-10, 11/20).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CartRetryWiringTest {

    private static final String CONFLICT =
            "{\"status\":409,\"code\":\"CART_UPDATE_CONFLICT\",\"message\":\"장바구니가 동시에 수정되었습니다. 다시 시도해 주세요.\"}";

    @MockitoSpyBean CartService cartService;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private long userId;
    private long americano;

    @BeforeEach
    void 준비() {
        userId = userRepository.saveAndFlush(new User("배선테스터")).getId();
        americano = jdbc.queryForObject("SELECT id FROM menus WHERE name = '아메리카노' ORDER BY id LIMIT 1", Long.class);
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException("CartItem", 1L);
    }

    @Test
    void 담기_충돌이_계속되면_3번_시도한_뒤_409() throws Exception {
        doThrow(conflict()).when(cartService).add(anyLong(), anyLong(), anyInt());

        MockHttpServletResponse res = add("{\"menuId\":" + americano + ",\"quantity\":1}");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CONFLICT);
        verify(cartService, times(3)).add(userId, americano, 1);
    }

    @Test
    void 담기_충돌이_2번이면_세_번째에_실제로_저장된다() throws Exception {
        doThrow(conflict()).doThrow(conflict()).doCallRealMethod()
                .when(cartService).add(anyLong(), anyLong(), anyInt());

        MockHttpServletResponse res = add("{\"menuId\":" + americano + ",\"quantity\":2}");

        assertThat(res.getStatus()).isEqualTo(200);
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM cart_items WHERE user_id = ? AND menu_id = ?", Integer.class, userId, americano))
                .isEqualTo(2);
    }

    @Test
    void 같은_메뉴_첫_담기_UNIQUE_위반이_계속되면_409() throws Exception {
        doThrow(new DataIntegrityViolationException("uk_cart_items_user_id_menu_id"))
                .when(cartService).add(anyLong(), anyLong(), anyInt());

        MockHttpServletResponse res = add("{\"menuId\":" + americano + ",\"quantity\":1}");

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CONFLICT);
        verify(cartService, times(2)).add(userId, americano, 1);
    }

    @Test
    void 수량_변경_충돌이_계속되면_409() throws Exception {
        doThrow(conflict()).when(cartService).changeQuantity(anyLong(), anyLong(), anyInt());

        MockHttpServletResponse res = mockMvc.perform(patch("/api/users/{id}/cart/items/{m}", userId, americano)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CONFLICT);
        verify(cartService, times(3)).changeQuantity(userId, americano, 3);
    }

    @Test
    void 삭제_충돌이_계속되면_409() throws Exception {
        doThrow(conflict()).when(cartService).remove(anyLong(), anyLong());

        MockHttpServletResponse res = mockMvc.perform(delete("/api/users/{id}/cart/items/{m}", userId, americano))
                .andReturn().getResponse();

        assertThat(res.getContentAsString(UTF_8)).isEqualTo(CONFLICT);
        verify(cartService, times(3)).remove(userId, americano);
    }

    private MockHttpServletResponse add(String body) throws Exception {
        return mockMvc.perform(post("/api/users/{id}/cart/items", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
}
