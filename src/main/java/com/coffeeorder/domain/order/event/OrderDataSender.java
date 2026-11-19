package com.coffeeorder.domain.order.event;

/**
 * 주문 데이터를 외부 데이터 플랫폼으로 보낸다(주문당 1회: 사용자 ID, 메뉴 ID 목록, 결제 금액).
 * 이번 주는 로그로 흉내 내는 {@link LoggingOrderDataSender} 하나이고, 2주차 Kafka 확장 때 구현체를 바꾼다(노션 기술 명세 6).
 */
public interface OrderDataSender {

    void send(OrderCompletedEvent event);
}
