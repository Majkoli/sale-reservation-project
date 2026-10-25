package com.flashsale.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HoldResponse {

    private UUID reservationId;
    private String sku;
    private int quantity;
    private Instant expiresAt;
}
