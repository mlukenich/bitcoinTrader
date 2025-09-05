package com.example.tradingbot;

import com.example.tradingbot.model.BotState;
import com.example.tradingbot.repository.BotStateRepository;
import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.NotificationService;
import com.example.tradingbot.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link TradingService}.
 */
@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock
    private BotStateRepository botStateRepository;
    @Mock
    private BotStateService botStateService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private TradingService tradingService;

    private BotState testState;

    @BeforeEach
    void setUp() {
        // 1. Setup the bot's state
        testState = new BotState();
        testState.setInPosition(false);
        for (int i = 0; i < 51; i++) {
            testState.getPriceHistory().add(100.0);
        }

        when(botStateRepository.findById(1L)).thenReturn(Optional.of(testState));
        when(botStateRepository.save(any(BotState.class))).thenReturn(testState);

        // 2. Set simple strategy parameters for a clear signal
        tradingService.setSymbol("BTC/USD");
        tradingService.setShortMaPeriod(10);
        tradingService.setLongMaPeriod(50);
        tradingService.setRsiPeriod(14);
        tradingService.setRiskPercentage(0.01);

        // 3. Initialize the service to load the mocked state
        tradingService.init();
    }

    @Test
    void shouldPlaceBuyOrder_whenConditionsAreMet() {
        // --- ARRANGE ---

        // 1. Mock the API response for the price fetch
        TradingService.AlpacaBar fakeBar = new TradingService.AlpacaBar(110, 110, 110, 110);
        TradingService.AlpacaBarsResponse fakeBarsResponse = new TradingService.AlpacaBarsResponse();
        fakeBarsResponse.setBars(Map.of("BTC/USD", fakeBar));
        ResponseEntity<TradingService.AlpacaBarsResponse> fakePriceResponse = new ResponseEntity<>(fakeBarsResponse, HttpStatus.OK);
        when(restTemplate.exchange(contains("/v1beta3/crypto"), eq(HttpMethod.GET), any(), eq(TradingService.AlpacaBarsResponse.class)))
                .thenReturn(fakePriceResponse);

        // 2. Mock the account status for the buy() method
        TradingService.AlpacaAccount fakeAccount = new TradingService.AlpacaAccount();
        fakeAccount.setEquity(10000.0);
        ResponseEntity<TradingService.AlpacaAccount> fakeAccountResponse = new ResponseEntity<>(fakeAccount, HttpStatus.OK);
        when(restTemplate.exchange(contains("/v2/account"), eq(HttpMethod.GET), any(), eq(TradingService.AlpacaAccount.class)))
                .thenReturn(fakeAccountResponse);

        // 3. Manually create a crossover scenario
        testState.setPreviousShortMA(99.0);
        testState.setPreviousLongMA(100.0);

        // 4. Force a high RSI to ensure the signal fires
        TradingService spyTradingService = spy(tradingService);
        doReturn(60.0).when(spyTradingService).calculateRsi(any());

        // --- ACT ---
        spyTradingService.executeStrategy();

        // --- ASSERT ---
        verify(restTemplate, times(1)).postForObject(contains("/v2/orders"), any(HttpEntity.class), eq(String.class));
    }
}
