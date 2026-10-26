package com.flashsale.reservation.worker;

import com.flashsale.reservation.domain.Reservation;
import com.flashsale.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationReconciliationWorker {

    private final ReservationRepository reservationRepository;
    private final ReservationExpirationProcessor expirationProcessor;

    @Scheduled(fixedRate = 60000)
    public int reconcileExpiredReservations() {
        List<Reservation> expiredReservations = reservationRepository.findExpiredReservations(Instant.now());
        int processedCount = 0;

        for (Reservation reservation : expiredReservations) {
            try {
                if (expirationProcessor.expireReservation(reservation.getId())) {
                    processedCount++;
                }
            } catch (Exception ex) {
                log.error("Failed to reconcile expired reservation {}: {}",
                        reservation.getId(), ex.getMessage(), ex);
            }
        }

        return processedCount;
    }
}
