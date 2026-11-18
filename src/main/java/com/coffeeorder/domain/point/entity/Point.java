package com.coffeeorder.domain.point.entity;

import com.coffeeorder.domain.user.entity.User;
import com.coffeeorder.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/** 사용자별 포인트 잔액(1:1). 충전·차감 때 이 행에 비관적 락을 건다(다음 묶음). */
@Getter
@Entity
@Table(name = "points", uniqueConstraints = @UniqueConstraint(name = "uk_points_user_id", columnNames = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Point extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ColumnDefault("0")
    @Column(nullable = false)
    private long balance;

    public Point(User user, long balance) {
        this.user = user;
        this.balance = balance;
    }
}
