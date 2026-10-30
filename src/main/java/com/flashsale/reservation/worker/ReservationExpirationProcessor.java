package com.flashsale.reservation.worker;

import com.flashsale.reservation.domain.ReservationStatus;
import com.flashsale.reservation.repository.ReservationRepository;
import com.flashsale.reservation.service.RedisReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationProcessor {

    private final ReservationRepository reservationRepository;
    private final RedisReservationService redisReservationService;

    /**
     * Processes a single expired reservation in an isolated transaction with pessimistic row locking.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireReservation(UUID reservationId) {
        return reservationRepository.findByIdForUpdate(reservationId)
                .or(() -> reservationRepository.findById(reservationId))
                .filter(reservation -> reservation.getStatus() == ReservationStatus.HELD)
                .map(reservation -> {
                    reservation.setStatus(ReservationStatus.EXPIRED);
                    reservationRepository.save(reservation);

                    redisReservationService.releaseStockHold(
                            reservation.getSku(),
                            reservation.getId(),
                            reservation.getQuantity()
                    );

                    log.info("Reservation expired: {}, refunded {} to SKU {}",
                            reservation.getId(), reservation.getQuantity(), reservation.getSku());
                    return true;
                })
                .orElse(false);
    }
}
