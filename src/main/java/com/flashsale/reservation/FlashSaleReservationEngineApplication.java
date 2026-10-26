package com.flashsale.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlashSaleReservationEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSaleReservationEngineApplication.class, args);
    }
}
