package com.flashsale.reservation.worker;

import com.flashsale.reservation.domain.Reservation;
import com.flashsale.reservation.domain.ReservationStatus;
import com.flashsale.reservation.repository.ReservationRepository;
import com.flashsale.reservation.service.RedisReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationReconciliationWorkerTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RedisReservationService redisReservationService;

    private ReservationExpirationProcessor expirationProcessor;
    private ReservationReconciliationWorker reconciliationWorker;

    @BeforeEach
    void setUp() {
        expirationProcessor = new ReservationExpirationProcessor(reservationRepository, redisReservationService);
        reconciliationWorker = new ReservationReconciliationWorker(reservationRepository, expirationProcessor);
    }

    @Test
    @DisplayName("reconcileExpiredReservations marks HELD reservation as EXPIRED and releases Redis stock")
    void reconcileExpiredReservations_marksExpiredAndRefundsRedisStock() {
        UUID reservationId = UUID.randomUUID();
        Reservation expired = Reservation.builder()
                .id(reservationId)
                .userId("user-1")
                .sku("FLASH-TICKET")
                .quantity(3)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().minusSeconds(10))
                .build();

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(expired));
        when(reservationRepository.findByIdForUpdate(reservationId))
                .thenReturn(Optional.of(expired));

        int processed = reconciliationWorker.reconcileExpiredReservations();

        assertThat(processed).isEqualTo(1);
        assertThat(expired.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(reservationRepository).save(expired);
        verify(redisReservationService).releaseStockHold("FLASH-TICKET", reservationId, 3);
    }

    @Test
    @DisplayName("reconcileExpiredReservations isolates failures per reservation so remaining holds still reclaim")
    void reconcileExpiredReservations_whenOneFails_continuesReclaimingOthers() {
        UUID failingId = UUID.randomUUID();
        UUID succeedingId = UUID.randomUUID();

        Reservation failing = Reservation.builder()
                .id(failingId)
                .userId("user-1")
                .sku("FLASH-TICKET")
                .quantity(1)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().minusSeconds(15))
                .build();

        Reservation succeeding = Reservation.builder()
                .id(succeedingId)
                .userId("user-2")
                .sku("FLASH-TICKET")
                .quantity(2)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().minusSeconds(15))
                .build();

        when(reservationRepository.findExpiredReservations(any(Instant.class)))
                .thenReturn(List.of(failing, succeeding));
        when(reservationRepository.findByIdForUpdate(failingId))
                .thenThrow(new RuntimeException("Simulated transient lock timeout"));
        when(reservationRepository.findByIdForUpdate(succeedingId))
                .thenReturn(Optional.of(succeeding));

        int processed = reconciliationWorker.reconcileExpiredReservations();

        assertThat(processed).isEqualTo(1);
        assertThat(succeeding.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(redisReservationService).releaseStockHold("FLASH-TICKET", succeedingId, 2);
    }
}
