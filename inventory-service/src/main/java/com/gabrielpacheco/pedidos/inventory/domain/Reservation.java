package com.gabrielpacheco.pedidos.inventory.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationLine> lines = new ArrayList<>();

    protected Reservation() {
    }

    public Reservation(UUID orderId, ReservationStatus status, BigDecimal amount, Map<String, Integer> quantities, Instant now) {
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
        this.updatedAt = now;
        quantities.forEach((sku, quantity) -> lines.add(new ReservationLine(this, sku, quantity)));
    }

    public void moveTo(ReservationStatus target, Instant now) {
        this.status = target;
        this.updatedAt = now;
    }

    public Map<String, Integer> quantities() {
        return lines.stream().collect(Collectors.toMap(ReservationLine::getSku, ReservationLine::getQuantity));
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
