# 미룬 것 (Deferred)

| 번호 | 내용 | 미룬 이유 | 언제 |
|---|---|---|---|
| D-1 | `BaseEntity` 종단 검증 (실제 저장 시 `created_at`/`updated_at` 이 기록되는지) | 엔티티가 아직 없고, 테스트 전용 엔티티를 두면 로컬 DB에 테스트 테이블이 생김 | 11/18 첫 엔티티(users) 작성 때 `@SpringBootTest @Transactional` 로 저장 후, **`JdbcTemplate` 으로 DB 원본 문자열을 읽어** 비교한다(JPA 로 되읽으면 시간대 오류가 가려진다). 시각은 `Clock.fixed` 빈으로 고정하고, 순수 `@MockitoBean Clock` 은 `getZone()`/`instant()` 가 null 이라 NPE 가 난다 |
| D-2 | 매핑표에 없는 스프링 4xx 예외(예: `HttpMediaTypeNotAcceptableException` 406)는 현재 `INTERNAL_SERVER_ERROR` 500 으로 응답됨 | 이 프로젝트에서 실제로 날 가능성이 낮고, 나면 ERROR 로그로 드러남 | 실제로 발생하면 `GlobalExceptionHandler` 에 매핑 추가 |
| D-4 | 배포 서버(JVM)의 시간대는 **UTC 또는 Asia/Seoul** 로 둔다. 서머타임이 있는 시간대(예: America/New_York)에서는 서머타임 전환 시각(존재하지 않는 벽시계 시각)이 1시간 밀려 저장될 수 있음 | `serverTimezone` 제거(2026-11-17 결정)의 잔여 한계. UTC·서울은 서머타임이 없어 해당 없음 | 배포 환경을 정할 때(README·실행 옵션에 명시) |
| D-3 | 11/18 엔티티 작성 시 주의 (리뷰에서 나온 것) | 지금은 엔티티·쿼리가 없어 코드로 증명 불가 | ① `@DataJpaTest` 같은 슬라이스 테스트는 `ClockConfig`·`JpaAuditingConfig` 를 자동으로 안 읽으므로 `@Import` 필요(없으면 `created_at` NOT NULL 위반) ② `orders`·`point_histories` 는 BaseEntity 를 상속하지 않으므로 `created_at` 을 직접 매핑할 때 `@CreatedDate` + `AuditingEntityListener` 를 써서 같은 Clock 을 타게 할 것(`LocalDateTime.now()` 직접 호출 금지) ③ 벌크 UPDATE(`@Modifying`)는 `@PreUpdate` 를 안 타서 `updated_at` 이 갱신되지 않음 — 포인트 차감을 조건부 UPDATE 로 구현하면 직접 갱신해야 함 |
