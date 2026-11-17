package com.coffeeorder.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 서울 벽시계 시각이 서버(JVM) 시간대와 상관없이 DB 에 그대로 저장되는지 확인한다.
 * JDBC URL 에 serverTimezone 이 있으면 드라이버가 시각을 한 번 더 환산해서, UTC 서버에서 9시간 어긋난다.
 */
@SpringBootTest
class DatabaseTimeZoneTest {

    @Autowired
    private Environment env;

    @Test
    void UTC_서버에서도_서울_09시는_DB_원본에_09시로_저장된다() throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // 드라이버가 접속 시점의 JVM 시간대를 쓰므로, 시간대를 바꾼 뒤 새 연결을 직접 연다.
        try (Connection con = DriverManager.getConnection(
                env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
             Statement st = con.createStatement()) {
            st.execute("CREATE TEMPORARY TABLE tz_probe (ts DATETIME(6))");
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO tz_probe VALUES (?)")) {
                ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.of(2026, 11, 17, 9, 0)));
                ps.executeUpdate();
            }
            try (ResultSet rs = st.executeQuery("SELECT CAST(ts AS CHAR) FROM tz_probe")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).startsWith("2026-11-17 09:00:00");
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
