package com.flashsale.reservation.service;

import com.flashsale.reservation.domain.InventoryItem;
import com.flashsale.reservation.domain.Order;
import com.flashsale.reservation.domain.OrderStatus;
import com.flashsale.reservation.domain.Reservation;
import com.flashsale.reservation.domain.ReservationStatus;
import com.flashsale.reservation.domain.WalletAccount;
import com.flashsale.reservation.dto.CancelRequest;
import com.flashsale.reservation.dto.CancelResponse;
import com.flashsale.reservation.dto.CheckoutRequest;
import com.flashsale.reservation.dto.CheckoutResponse;
import com.flashsale.reservation.dto.HoldRequest;
import com.flashsale.reservation.dto.HoldResponse;
import com.flashsale.reservation.exception.InsufficientFundsException;
import com.flashsale.reservation.exception.OutOfStockException;
import com.flashsale.reservation.repository.InventoryItemRepository;
import com.flashsale.reservation.repository.OrderRepository;
import com.flashsale.reservation.repository.ReservationRepository;
import com.flashsale.reservation.repository.WalletAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceUnitTest {

    @Mock
    private InventoryItemRepository inventoryItemRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private WalletAccountRepository walletAccountRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RedisReservationService redisReservationService;

    @InjectMocks
    private CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(checkoutService, "reservationTtlSeconds", 600L);
    }

    @Test
    void reserveItem_whenRedisStockAvailable_persistsHeldReservation() {
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .sku("FLASH-TICKET")
                .name("VIP Ticket")
                .totalStock(10)
                .availableStock(10)
                .priceMinor(50_000L)
                .build();

        when(inventoryItemRepository.findBySku("FLASH-TICKET")).thenReturn(Optional.of(item));
        when(redisReservationService.acquireStockHold(eq("FLASH-TICKET"), eq("user-1"), any(UUID.class), eq(2)))
                .thenReturn(true);

        HoldResponse response = checkoutService.reserveItem(HoldRequest.builder()
                .sku("FLASH-TICKET")
                .userId("user-1")
                .quantity(2)
                .build());

        assertThat(response.getReservationId()).isNotNull();
        assertThat(response.getSku()).isEqualTo("FLASH-TICKET");
        assertThat(response.getQuantity()).isEqualTo(2);
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    void reserveItem_whenRedisHoldFails_throwsOutOfStockException() {
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .sku("FLASH-TICKET")
                .name("VIP Ticket")
                .totalStock(10)
                .availableStock(0)
                .priceMinor(50_000L)
                .build();

        when(inventoryItemRepository.findBySku("FLASH-TICKET")).thenReturn(Optional.of(item));
        when(redisReservationService.acquireStockHold(eq("FLASH-TICKET"), eq("user-1"), any(UUID.class), eq(1)))
                .thenReturn(false);

        assertThatThrownBy(() -> checkoutService.reserveItem(HoldRequest.builder()
                .sku("FLASH-TICKET")
                .userId("user-1")
                .quantity(1)
                .build()))
                .isInstanceOf(OutOfStockException.class)
                .hasMessage("Item out of stock");
    }

    @Test
    void checkout_whenSufficientBalance_deductsWalletAndStockAndCreatesPaidOrder() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .userId("user-1")
                .sku("FLASH-TICKET")
                .quantity(2)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        WalletAccount wallet = WalletAccount.builder()
                .id(UUID.randomUUID())
                .userId("user-1")
                .balanceMinor(150_000L)
                .currency("SEK")
                .build();

        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .sku("FLASH-TICKET")
                .name("VIP Ticket")
                .totalStock(10)
                .availableStock(10)
                .priceMinor(50_000L)
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(walletAccountRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(wallet));
        when(inventoryItemRepository.findBySkuForUpdate("FLASH-TICKET")).thenReturn(Optional.of(item));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        CheckoutResponse response = checkoutService.checkout(CheckoutRequest.builder()
                .reservationId(reservationId)
                .userId("user-1")
                .build());

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PAID.name());
        assertThat(response.getTotalDeductedMinor()).isEqualTo(100_000L);
        assertThat(wallet.getBalanceMinor()).isEqualTo(50_000L);
        assertThat(item.getAvailableStock()).isEqualTo(8);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(redisReservationService).confirmStockHold(reservationId);
    }

    @Test
    void checkout_whenInsufficientFunds_throwsInsufficientFundsException() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .userId("user-1")
                .sku("FLASH-TICKET")
                .quantity(1)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        WalletAccount wallet = WalletAccount.builder()
                .id(UUID.randomUUID())
                .userId("user-1")
                .balanceMinor(20_000L)
                .currency("SEK")
                .build();

        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .sku("FLASH-TICKET")
                .name("VIP Ticket")
                .totalStock(10)
                .availableStock(10)
                .priceMinor(50_000L)
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(walletAccountRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(wallet));
        when(inventoryItemRepository.findBySkuForUpdate("FLASH-TICKET")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> checkoutService.checkout(CheckoutRequest.builder()
                .reservationId(reservationId)
                .userId("user-1")
                .build()))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void cancelReservation_whenHeld_marksCancelledAndReleasesRedisStock() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .userId("user-1")
                .sku("FLASH-TICKET")
                .quantity(3)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        CancelResponse response = checkoutService.cancelReservation(CancelRequest.builder()
                .reservationId(reservationId)
                .userId("user-1")
                .build());

        assertThat(response.getStatus()).isEqualTo(ReservationStatus.CANCELLED.name());
        verify(redisReservationService).releaseStockHold("FLASH-TICKET", reservationId, 3);
    }
}
