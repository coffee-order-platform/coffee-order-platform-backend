# 미룬 것 (Deferred)

| 번호 | 내용 | 미룬 이유 | 언제 |
|---|---|---|---|
| D-1 | `BaseEntity` 종단 검증 (실제 저장 시 `created_at`/`updated_at` 이 서울 시각으로 기록되는지) | 엔티티가 아직 없고, 테스트 전용 엔티티를 두면 로컬 DB에 테스트 테이블이 생김 | 11/18 첫 엔티티(users) 작성 때 `@SpringBootTest @Transactional` + `@MockitoBean Clock` 으로 |
| D-2 | 매핑표에 없는 스프링 4xx 예외(예: `HttpMediaTypeNotAcceptableException` 406)는 현재 `INTERNAL_SERVER_ERROR` 500 으로 응답됨 | 이 프로젝트에서 실제로 날 가능성이 낮고, 나면 ERROR 로그로 드러남 | 실제로 발생하면 `GlobalExceptionHandler` 에 매핑 추가 |
