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
import com.flashsale.reservation.exception.InvalidReservationStateException;
import com.flashsale.reservation.exception.OutOfStockException;
import com.flashsale.reservation.exception.ResourceNotFoundException;
import com.flashsale.reservation.repository.InventoryItemRepository;
import com.flashsale.reservation.repository.OrderRepository;
import com.flashsale.reservation.repository.ReservationRepository;
import com.flashsale.reservation.repository.WalletAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {

    private final InventoryItemRepository inventoryItemRepository;
    private final ReservationRepository reservationRepository;
    private final WalletAccountRepository walletAccountRepository;
    private final OrderRepository orderRepository;
    private final RedisReservationService redisReservationService;

    @Value("${reservation.ttl-seconds:600}")
    private long reservationTtlSeconds;

    /**
     * Executes atomic Redis Lua hold and persists a HELD Reservation in PostgreSQL.
     */
    @Transactional
    public HoldResponse reserveItem(HoldRequest request) {
        InventoryItem item = inventoryItemRepository.findBySku(request.getSku())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory item not found for SKU: " + request.getSku()));

        redisReservationService.initializeStockIfAbsent(item.getSku(), item.getAvailableStock());

        UUID reservationId = UUID.randomUUID();
        boolean acquired = redisReservationService.acquireStockHold(
                item.getSku(),
                request.getUserId(),
                reservationId,
                request.getQuantity()
        );

        if (!acquired) {
            throw new OutOfStockException("Item out of stock");
        }

        Instant expiresAt = Instant.now().plusSeconds(reservationTtlSeconds);
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .userId(request.getUserId())
                .sku(item.getSku())
                .quantity(request.getQuantity())
                .status(ReservationStatus.HELD)
                .expiresAt(expiresAt)
                .build();

        reservationRepository.save(reservation);

        return HoldResponse.builder()
                .reservationId(reservationId)
                .sku(item.getSku())
                .quantity(request.getQuantity())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Settles a held reservation inside an ACID transaction using pessimistic wallet locking.
     */
    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request) {
        Reservation reservation = reservationRepository.findById(request.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + request.getReservationId()));

        if (!reservation.getUserId().equals(request.getUserId())) {
            throw new InvalidReservationStateException("Reservation does not belong to user: " + request.getUserId());
        }

        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new InvalidReservationStateException("Reservation is not in HELD status: " + reservation.getStatus());
        }

        if (!reservation.getExpiresAt().isAfter(Instant.now())) {
            throw new InvalidReservationStateException("Reservation has expired: " + reservation.getId());
        }

        WalletAccount wallet = walletAccountRepository.findByUserIdForUpdate(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet account not found for user: " + request.getUserId()));

        InventoryItem item = inventoryItemRepository.findBySkuForUpdate(reservation.getSku())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory item not found for SKU: " + reservation.getSku()));

        long totalCost = (long) reservation.getQuantity() * item.getPriceMinor();

        if (wallet.getBalanceMinor() < totalCost) {
            throw new InsufficientFundsException("Insufficient wallet balance. Required: "
                    + totalCost + " " + wallet.getCurrency() + " minor, available: " + wallet.getBalanceMinor());
        }

        if (item.getAvailableStock() < reservation.getQuantity()) {
            throw new OutOfStockException("Item out of stock");
        }

        wallet.setBalanceMinor(wallet.getBalanceMinor() - totalCost);
        walletAccountRepository.save(wallet);

        item.setAvailableStock(item.getAvailableStock() - reservation.getQuantity());
        inventoryItemRepository.save(item);

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        Order order = Order.builder()
                .reservationId(reservation.getId())
                .userId(request.getUserId())
                .totalAmountMinor(totalCost)
                .currency(wallet.getCurrency())
                .status(OrderStatus.PAID)
                .build();
        Order savedOrder = orderRepository.save(order);

        redisReservationService.confirmStockHold(reservation.getId());

        return CheckoutResponse.builder()
                .orderId(savedOrder.getId())
                .status(savedOrder.getStatus().name())
                .totalDeductedMinor(totalCost)
                .currency(savedOrder.getCurrency())
                .build();
    }

    /**
     * Proactively cancels a held reservation and restores the Redis stock hold immediately.
     */
    @Transactional
    public CancelResponse cancelReservation(CancelRequest request) {
        Reservation reservation = reservationRepository.findById(request.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + request.getReservationId()));

        if (!reservation.getUserId().equals(request.getUserId())) {
            throw new InvalidReservationStateException("Reservation does not belong to user: " + request.getUserId());
        }

        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new InvalidReservationStateException("Only HELD reservations can be cancelled. Current status: " + reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);

        redisReservationService.releaseStockHold(reservation.getSku(), reservation.getId(), reservation.getQuantity());
        log.info("Reservation cancelled: {}, refunded {} to SKU {}",
                reservation.getId(), reservation.getQuantity(), reservation.getSku());

        return CancelResponse.builder()
                .reservationId(reservation.getId())
                .status(reservation.getStatus().name())
                .build();
    }
}
