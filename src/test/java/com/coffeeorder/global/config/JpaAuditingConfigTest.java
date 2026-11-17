package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class JpaAuditingConfigTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void 기본_시계는_서울_시간대다() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(SEOUL);
    }

    @Test
    void UTC_15시는_서울_다음날_0시로_기록된다() {
        // UTC 와 서울의 날짜가 갈리는 자정 경계
        Clock fixed = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), SEOUL);

        var now = new JpaAuditingConfig().auditingDateTimeProvider(fixed).getNow();

        assertThat(now).contains(LocalDateTime.of(2026, 11, 17, 0, 0));
    }
}
