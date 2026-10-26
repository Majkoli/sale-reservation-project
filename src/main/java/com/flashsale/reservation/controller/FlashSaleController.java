package com.flashsale.reservation.controller;

import com.flashsale.reservation.dto.CheckoutRequest;
import com.flashsale.reservation.dto.CheckoutResponse;
import com.flashsale.reservation.dto.HoldRequest;
import com.flashsale.reservation.dto.HoldResponse;
import com.flashsale.reservation.service.CheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
public class FlashSaleController {

    private final CheckoutService checkoutService;

    @PostMapping("/reserve")
    public ResponseEntity<HoldResponse> reserve(@Valid @RequestBody HoldRequest request) {
        HoldResponse response = checkoutService.reserveItem(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        CheckoutResponse response = checkoutService.checkout(request);
        return ResponseEntity.ok(response);
    }
}
