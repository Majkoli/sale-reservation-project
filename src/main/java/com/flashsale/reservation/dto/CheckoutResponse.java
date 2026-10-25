package com.flashsale.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckoutResponse {

    private UUID orderId;
    private String status;
    private Long totalDeductedMinor;
    private String currency;
}
