package com.flashsale.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.reservation.dto.CancelRequest;
import com.flashsale.reservation.dto.CancelResponse;
import com.flashsale.reservation.dto.CheckoutRequest;
import com.flashsale.reservation.dto.CheckoutResponse;
import com.flashsale.reservation.dto.HoldRequest;
import com.flashsale.reservation.dto.HoldResponse;
import com.flashsale.reservation.exception.InsufficientFundsException;
import com.flashsale.reservation.exception.OutOfStockException;
import com.flashsale.reservation.exception.ResourceNotFoundException;
import com.flashsale.reservation.service.CheckoutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlashSaleController.class)
class FlashSaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CheckoutService checkoutService;

    @Test
    @DisplayName("POST /api/v1/sales/reserve returns 201 Created when hold succeeds")
    void reserve_whenStockAvailable_returns201Created() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(600);

        when(checkoutService.reserveItem(any(HoldRequest.class)))
                .thenReturn(HoldResponse.builder()
                        .reservationId(reservationId)
                        .sku("FLASH-TICKET")
                        .quantity(1)
                        .expiresAt(expiresAt)
                        .build());

        HoldRequest request = HoldRequest.builder()
                .sku("FLASH-TICKET")
                .userId("user-1")
                .quantity(1)
                .build();

        mockMvc.perform(post("/api/v1/sales/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.sku").value("FLASH-TICKET"))
                .andExpect(jsonPath("$.quantity").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/sales/reserve returns 409 Conflict when sold out")
    void reserve_whenSoldOut_returns409Conflict() throws Exception {
        when(checkoutService.reserveItem(any(HoldRequest.class)))
                .thenThrow(new OutOfStockException("Item out of stock"));

        HoldRequest request = HoldRequest.builder()
                .sku("FLASH-TICKET")
                .userId("user-1")
                .quantity(1)
                .build();

        mockMvc.perform(post("/api/v1/sales/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Item out of stock"));
    }

    @Test
    @DisplayName("POST /api/v1/sales/reserve returns 400 Bad Request when quantity < 1")
    void reserve_whenInvalidQuantity_returns400BadRequest() throws Exception {
        HoldRequest request = HoldRequest.builder()
                .sku("FLASH-TICKET")
                .userId("user-1")
                .quantity(0)
                .build();

        mockMvc.perform(post("/api/v1/sales/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/sales/checkout returns 200 OK when settlement succeeds")
    void checkout_whenValid_returns200Ok() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(checkoutService.checkout(any(CheckoutRequest.class)))
                .thenReturn(CheckoutResponse.builder()
                        .orderId(orderId)
                        .status("PAID")
                        .totalDeductedMinor(50_000L)
                        .currency("SEK")
                        .build());

        CheckoutRequest request = CheckoutRequest.builder()
                .reservationId(reservationId)
                .userId("user-1")
                .build();

        mockMvc.perform(post("/api/v1/sales/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalDeductedMinor").value(50_000))
                .andExpect(jsonPath("$.currency").value("SEK"));
    }

    @Test
    @DisplayName("POST /api/v1/sales/checkout returns 400 Bad Request on InsufficientFundsException")
    void checkout_whenInsufficientFunds_returns400BadRequest() throws Exception {
        when(checkoutService.checkout(any(CheckoutRequest.class)))
                .thenThrow(new InsufficientFundsException("Insufficient wallet balance"));

        CheckoutRequest request = CheckoutRequest.builder()
                .reservationId(UUID.randomUUID())
                .userId("user-1")
                .build();

        mockMvc.perform(post("/api/v1/sales/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Insufficient wallet balance"));
    }

    @Test
    @DisplayName("POST /api/v1/sales/cancel returns 200 OK when reservation is cancelled")
    void cancel_whenValid_returns200Ok() throws Exception {
        UUID reservationId = UUID.randomUUID();

        when(checkoutService.cancelReservation(any(CancelRequest.class)))
                .thenReturn(CancelResponse.builder()
                        .reservationId(reservationId)
                        .status("CANCELLED")
                        .build());

        CancelRequest request = CancelRequest.builder()
                .reservationId(reservationId)
                .userId("user-1")
                .build();

        mockMvc.perform(post("/api/v1/sales/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/v1/sales/checkout returns 404 Not Found when reservation does not exist")
    void checkout_whenReservationMissing_returns404NotFound() throws Exception {
        when(checkoutService.checkout(any(CheckoutRequest.class)))
                .thenThrow(new ResourceNotFoundException("Reservation not found"));

        CheckoutRequest request = CheckoutRequest.builder()
                .reservationId(UUID.randomUUID())
                .userId("user-1")
                .build();

        mockMvc.perform(post("/api/v1/sales/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
