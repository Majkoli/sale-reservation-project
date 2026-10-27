package com.flashsale.reservation;

import com.flashsale.reservation.domain.InventoryItem;
import com.flashsale.reservation.domain.Reservation;
import com.flashsale.reservation.domain.ReservationStatus;
import com.flashsale.reservation.domain.WalletAccount;
import com.flashsale.reservation.dto.CheckoutRequest;
import com.flashsale.reservation.dto.CheckoutResponse;
import com.flashsale.reservation.dto.HoldRequest;
import com.flashsale.reservation.dto.HoldResponse;
import com.flashsale.reservation.repository.InventoryItemRepository;
import com.flashsale.reservation.repository.OrderRepository;
import com.flashsale.reservation.repository.ReservationRepository;
import com.flashsale.reservation.repository.WalletAccountRepository;
import com.flashsale.reservation.service.RedisReservationService;
import com.flashsale.reservation.worker.ReservationReconciliationWorker;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class FlashSaleConcurrencyIntegrationTest {

    private static final String FLASH_TICKET_SKU = "FLASH-TICKET";
    private static final int INITIAL_STOCK = 10;
    private static final long TICKET_PRICE_MINOR = 50_000L;
    private static final long INITIAL_WALLET_BALANCE_MINOR = 100_000L;
    private static final int CONCURRENT_USERS = 50;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("flashsale_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static final RedisContainer redis = new RedisContainer(
            DockerImageName.parse("redis:7.2-alpine"));

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private WalletAccountRepository walletAccountRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RedisReservationService redisReservationService;

    @Autowired
    private ReservationReconciliationWorker reconciliationWorker;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        reservationRepository.deleteAll();
        walletAccountRepository.deleteAll();
        inventoryItemRepository.deleteAll();

        if (redisTemplate.getConnectionFactory() != null) {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        }

        inventoryItemRepository.save(InventoryItem.builder()
                .sku(FLASH_TICKET_SKU)
                .name("VIP Flash Sale Concert Ticket")
                .totalStock(INITIAL_STOCK)
                .availableStock(INITIAL_STOCK)
                .priceMinor(TICKET_PRICE_MINOR)
                .build());
        redisReservationService.setStock(FLASH_TICKET_SKU, INITIAL_STOCK);

        List<WalletAccount> wallets = IntStream.rangeClosed(1, CONCURRENT_USERS)
                .mapToObj(i -> WalletAccount.builder()
                        .userId("user-" + i)
                        .balanceMinor(INITIAL_WALLET_BALANCE_MINOR)
                        .currency("SEK")
                        .build())
                .toList();
        walletAccountRepository.saveAll(wallets);
    }

    @Test
    @DisplayName("Scenario 1: The Stampede - 50 concurrent threads competing for 10 units results in zero overselling")
    void scenario1_theStampede_preventsOverselling() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_USERS);
        CountDownLatch startGun = new CountDownLatch(1);

        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Future<ResponseEntity<HoldResponse>>> futures = new ArrayList<>();

        for (int i = 1; i <= CONCURRENT_USERS; i++) {
            final String userId = "user-" + i;
            Callable<ResponseEntity<HoldResponse>> task = () -> {
                readyLatch.countDown();
                startGun.await(10, TimeUnit.SECONDS);

                HoldRequest request = HoldRequest.builder()
                        .sku(FLASH_TICKET_SKU)
                        .userId(userId)
                        .quantity(1)
                        .build();

                ResponseEntity<HoldResponse> response = restTemplate.postForEntity(
                        "/api/v1/sales/reserve",
                        request,
                        HoldResponse.class
                );

                if (response.getStatusCode() == HttpStatus.CREATED) {
                    createdCount.incrementAndGet();
                } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                    conflictCount.incrementAndGet();
                }
                return response;
            };
            futures.add(executor.submit(task));
        }

        assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
        startGun.countDown();

        for (Future<ResponseEntity<HoldResponse>> future : futures) {
            ResponseEntity<HoldResponse> response = future.get(15, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.CREATED) {
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getReservationId()).isNotNull();
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(createdCount.get()).isEqualTo(10);
        assertThat(conflictCount.get()).isEqualTo(40);
        assertThat(redisReservationService.getStock(FLASH_TICKET_SKU)).isEqualTo(0);
        assertThat(reservationRepository.countByStatus(ReservationStatus.HELD)).isEqualTo(10L);
    }

    @Test
    @DisplayName("Scenario 2: Double-Spending Prevention on Wallet - concurrent checkouts on 50,000 öre wallet allow only one")
    void scenario2_doubleSpendingPreventionOnWallet() throws Exception {
        String userId = "double-spend-user";
        walletAccountRepository.save(WalletAccount.builder()
                .userId(userId)
                .balanceMinor(50_000L)
                .currency("SEK")
                .build());

        inventoryItemRepository.save(InventoryItem.builder()
                .sku("FLASH-HEADPHONES")
                .name("Wireless Noise-Cancelling Headphones")
                .totalStock(5)
                .availableStock(5)
                .priceMinor(50_000L)
                .build());
        redisReservationService.setStock("FLASH-HEADPHONES", 5);

        HoldResponse hold1 = restTemplate.postForEntity(
                "/api/v1/sales/reserve",
                HoldRequest.builder().sku(FLASH_TICKET_SKU).userId(userId).quantity(1).build(),
                HoldResponse.class
        ).getBody();

        HoldResponse hold2 = restTemplate.postForEntity(
                "/api/v1/sales/reserve",
                HoldRequest.builder().sku("FLASH-HEADPHONES").userId(userId).quantity(1).build(),
                HoldResponse.class
        ).getBody();

        assertThat(hold1).isNotNull();
        assertThat(hold2).isNotNull();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startGun = new CountDownLatch(1);

        List<UUID> reservationIds = List.of(hold1.getReservationId(), hold2.getReservationId());
        List<Future<ResponseEntity<CheckoutResponse>>> futures = new ArrayList<>();

        for (UUID reservationId : reservationIds) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startGun.await(5, TimeUnit.SECONDS);
                return restTemplate.postForEntity(
                        "/api/v1/sales/checkout",
                        CheckoutRequest.builder().reservationId(reservationId).userId(userId).build(),
                        CheckoutResponse.class
                );
            }));
        }

        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
        startGun.countDown();

        int okCount = 0;
        int badRequestCount = 0;

        for (Future<ResponseEntity<CheckoutResponse>> future : futures) {
            ResponseEntity<CheckoutResponse> response = future.get(10, TimeUnit.SECONDS);
            if (response.getStatusCode() == HttpStatus.OK) {
                okCount++;
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getOrderId()).isNotNull();
                assertThat(response.getBody().getTotalDeductedMinor()).isEqualTo(50_000L);
            } else if (response.getStatusCode() == HttpStatus.BAD_REQUEST) {
                badRequestCount++;
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(okCount).isEqualTo(1);
        assertThat(badRequestCount).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(1L);

        WalletAccount updatedWallet = walletAccountRepository.findByUserId(userId).orElseThrow();
        assertThat(updatedWallet.getBalanceMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Scenario 3: Automated Expiration & Reclamation - expired HELD reservation restores Redis stock and transitions to EXPIRED")
    void scenario3_automatedExpirationAndReclamation() {
        int reservedQty = 3;
        redisReservationService.setStock(FLASH_TICKET_SKU, INITIAL_STOCK - reservedQty);

        UUID expiredReservationId = UUID.randomUUID();
        Reservation expiredReservation = Reservation.builder()
                .id(expiredReservationId)
                .userId("user-1")
                .sku(FLASH_TICKET_SKU)
                .quantity(reservedQty)
                .status(ReservationStatus.HELD)
                .expiresAt(Instant.now().minusSeconds(5))
                .build();
        reservationRepository.save(expiredReservation);

        int reconciled = reconciliationWorker.reconcileExpiredReservations();

        assertThat(reconciled).isEqualTo(1);
        assertThat(redisReservationService.getStock(FLASH_TICKET_SKU)).isEqualTo(INITIAL_STOCK);

        Reservation reloaded = reservationRepository.findById(expiredReservationId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }
}
