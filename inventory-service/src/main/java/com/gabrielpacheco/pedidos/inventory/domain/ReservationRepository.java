package com.gabrielpacheco.pedidos.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Trava a reserva para que aprovação e recusa do mesmo pedido não sejam aplicadas juntas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.orderId = :orderId")
    Optional<Reservation> lockById(@Param("orderId") UUID orderId);
}
