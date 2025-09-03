package com.example.tradingbot;

import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.NotificationService;
import com.example.tradingbot.service.TradingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class}) // Use curly braces for syntax safety
class TradingServiceTest {

    @Mock
    private BotStateService botStateService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private RestTemplate restTemplate; // Mockito will create a mock of this

    @InjectMocks // and inject all of the above mocks into our service
    private TradingService tradingService;

    @Test
    void buy_shouldFormatNotionalValueToTwoDecimalPlaces() {
        // --- ARRANGE ---
        // 1. Create a fake account object with an equity that will produce a long decimal
        TradingService.AlpacaAccount fakeAccount = new TradingService.AlpacaAccount();
        fakeAccount.setEquity(10000.12345);

        // 2. Wrap the fake account in a ResponseEntity, which is what RestTemplate actually returns
        ResponseEntity<TradingService.AlpacaAccount> fakeResponse = new ResponseEntity<>(fakeAccount, HttpStatus.OK);

        // 3. THIS IS THE FIX: Mock the RestTemplate's exchange method.
        // Tell Mockito: "When the exchange method is called for an AlpacaAccount, return our fake response."
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(),
                eq(TradingService.AlpacaAccount.class)
        )).thenReturn(fakeResponse);

        // 4. Set the risk percentage for the test
        tradingService.setRiskPercentage(0.02);

        // --- ACT ---
        // Call the buy method. It will internally call getAccountStatus(), which will trigger our mock.
        tradingService.buy(60000.00);

        // --- ASSERT ---
        // 1. Create an ArgumentCaptor to capture the HttpEntity sent to RestTemplate's POST call
        ArgumentCaptor<HttpEntity> httpEntityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        // 2. Verify that postForObject was called, and capture the argument
        verify(restTemplate).postForObject(anyString(), httpEntityCaptor.capture(), any());

        // 3. Get the captured OrderRequest and check its notional value
        TradingService.OrderRequest capturedRequest = (TradingService.OrderRequest) httpEntityCaptor.getValue().getBody();

        // The formatted string should be "200.00"
        assertThat(capturedRequest.getNotional()).isEqualTo("200.00");
    }
}