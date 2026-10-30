package com.flashsale.reservation.repository;

import com.flashsale.reservation.domain.Reservation;
import com.flashsale.reservation.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT r FROM Reservation r WHERE r.status = com.flashsale.reservation.domain.ReservationStatus.HELD AND r.expiresAt < :now")
    List<Reservation> findExpiredReservations(@Param("now") Instant now);

    List<Reservation> findByStatus(ReservationStatus status);

    long countByStatus(ReservationStatus status);
}
