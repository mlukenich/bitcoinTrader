package com.example.tradingbot;

import com.example.tradingbot.config.AlpacaConfig;
import com.example.tradingbot.config.BotConfig;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingServiceTest {

    @Mock
    private BotStateRepository botStateRepository;
    @Mock
    private BotStateService botStateService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private AlpacaConfig alpacaConfig;
    @Mock
    private BotConfig botConfig;

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

        // 2. Mock the Alpaca and Bot configurations
        when(alpacaConfig.getBaseUrl()).thenReturn("https://paper-api.alpaca.markets");
        when(alpacaConfig.getDataUrl()).thenReturn("https://data.alpaca.markets");

        when(botConfig.getSymbol()).thenReturn("BTC/USD");
        when(botConfig.getShortMaPeriod()).thenReturn(10);
        when(botConfig.getLongMaPeriod()).thenReturn(50);
        when(botConfig.getRsiPeriod()).thenReturn(14);
        when(botConfig.getRiskPercentage()).thenReturn(0.01);


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

    @Test
    void shouldNotPlaceBuyOrder_whenApiErrorOccurs() {
        // --- ARRANGE ---
        when(restTemplate.exchange(contains("/v1beta3/crypto"), eq(HttpMethod.GET), any(), eq(TradingService.AlpacaBarsResponse.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error"));

        // --- ACT ---
        tradingService.executeStrategy();

        // --- ASSERT ---
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    void shouldNotPlaceBuyOrder_whenInsufficientFunds() {
        // --- ARRANGE ---
        TradingService.AlpacaBar fakeBar = new TradingService.AlpacaBar(110, 110, 110, 110);
        TradingService.AlpacaBarsResponse fakeBarsResponse = new TradingService.AlpacaBarsResponse();
        fakeBarsResponse.setBars(Map.of("BTC/USD", fakeBar));
        ResponseEntity<TradingService.AlpacaBarsResponse> fakePriceResponse = new ResponseEntity<>(fakeBarsResponse, HttpStatus.OK);
        when(restTemplate.exchange(contains("/v1beta3/crypto"), eq(HttpMethod.GET), any(), eq(TradingService.AlpacaBarsResponse.class)))
                .thenReturn(fakePriceResponse);

        TradingService.AlpacaAccount fakeAccount = new TradingService.AlpacaAccount();
        fakeAccount.setEquity(0.0); // No funds
        ResponseEntity<TradingService.AlpacaAccount> fakeAccountResponse = new ResponseEntity<>(fakeAccount, HttpStatus.OK);
        when(restTemplate.exchange(contains("/v2/account"), eq(HttpMethod.GET), any(), eq(TradingService.AlpacaAccount.class)))
                .thenReturn(fakeAccountResponse);

        testState.setPreviousShortMA(99.0);
        testState.setPreviousLongMA(100.0);

        TradingService spyTradingService = spy(tradingService);
        doReturn(60.0).when(spyTradingService).calculateRsi(any());

        // --- ACT ---
        spyTradingService.executeStrategy();

        // --- ASSERT ---
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }
}
