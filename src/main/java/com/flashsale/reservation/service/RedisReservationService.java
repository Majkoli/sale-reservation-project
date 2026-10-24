package com.flashsale.reservation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisReservationService {

    public static final String STOCK_KEY_PREFIX = "stock:";
    public static final String RESERVATION_KEY_PREFIX = "reservation:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<Long> holdStockScript;
    private final DefaultRedisScript<Long> releaseStockScript;
    private final ObjectMapper objectMapper;

    @Value("${reservation.ttl-seconds:600}")
    private long reservationTtlSeconds;

    /**
     * Atomically checks if Redis key stock:{sku} has sufficient quantity,
     * decrements it, and stores reservation:{reservationId} JSON with TTL.
     */
    public boolean acquireStockHold(String sku, String userId, UUID reservationId, int quantity) {
        String stockKey = stockKey(sku);
        String reservationKey = reservationKey(reservationId);
        String payloadJson = serializePayload(userId, sku, quantity);

        Long result = redisTemplate.execute(
                holdStockScript,
                List.of(stockKey, reservationKey),
                String.valueOf(quantity),
                String.valueOf(reservationTtlSeconds),
                payloadJson
        );

        return result != null && result == 1L;
    }

    /**
     * Refunds held quantity back to stock:{sku} and removes reservation:{reservationId}.
     */
    public long releaseStockHold(String sku, UUID reservationId) {
        return releaseStockHold(sku, reservationId, 0);
    }

    /**
     * Refunds held quantity back to stock:{sku} using either the Redis reservation payload
     * or the fallback quantity from PostgreSQL if the Redis key already expired.
     */
    public long releaseStockHold(String sku, UUID reservationId, int fallbackQuantity) {
        String stockKey = stockKey(sku);
        String reservationKey = reservationKey(reservationId);

        Long refunded = redisTemplate.execute(
                releaseStockScript,
                List.of(stockKey, reservationKey),
                String.valueOf(fallbackQuantity)
        );

        return refunded != null ? refunded : 0L;
    }

    /**
     * Deletes the temporary Redis reservation key once checkout settlement succeeds.
     */
    public void confirmStockHold(UUID reservationId) {
        redisTemplate.delete(reservationKey(reservationId));
    }

    /**
     * Atomically seeds stock:{sku} in Redis if the key does not exist yet (SETNX).
     */
    public void initializeStockIfAbsent(String sku, int availableStock) {
        redisTemplate.opsForValue().setIfAbsent(stockKey(sku), String.valueOf(availableStock));
    }

    /**
     * Explicitly sets stock:{sku} in Redis.
     */
    public void setStock(String sku, int availableStock) {
        redisTemplate.opsForValue().set(stockKey(sku), String.valueOf(availableStock));
    }

    /**
     * Reads current stock:{sku} value from Redis, or null if absent.
     */
    public Integer getStock(String sku) {
        String raw = redisTemplate.opsForValue().get(stockKey(sku));
        return raw != null ? Integer.valueOf(raw) : null;
    }

    public static String stockKey(String sku) {
        return STOCK_KEY_PREFIX + sku;
    }

    public static String reservationKey(UUID reservationId) {
        return RESERVATION_KEY_PREFIX + reservationId;
    }

    private String serializePayload(String userId, String sku, int quantity) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "userId", userId,
                    "sku", sku,
                    "quantity", quantity
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reservation payload", e);
        }
    }
}
