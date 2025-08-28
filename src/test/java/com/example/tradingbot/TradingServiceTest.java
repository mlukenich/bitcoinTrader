/*
package com.example.tradingbot;

import com.example.tradingbot.service.BotStateService;
import com.example.tradingbot.service.NotificationService;
import com.example.tradingbot.service.TradingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for the TradingService.
 * We use Mockito to simulate dependencies and isolate the service's logic.
 *//*
@ExtendWith(MockitoExtension.class)
class TradingServiceTest {

    @Mock
    private BotStateService botStateService; // Mock the BotStateService dependency

    @Mock
    private NotificationService notificationService; // Mock the NotificationService dependency

    @Spy // Use @Spy to allow calling real methods while still being able to stub others
    @InjectMocks // Inject the mocks into this instance of TradingService
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        // Set default configurations before each test
        tradingService.setLongMaPeriod(10);
        tradingService.setShortMaPeriod(3);
        tradingService.setRsiPeriod(10);
        // Make sure the buy/sell methods don't actually do anything, we just want to verify they are called
        doNothing().when(tradingService).buy(anyDouble());
        doNothing().when(tradingService).sell();
    }

    @Test
    void shouldPlaceBuyOrder_whenMaCrossesUp_andRsiIsBullish() {
        // Arrange: Create a price history that will trigger a buy signal
        List<TradingService.AlpacaBar> history = new ArrayList<>();
        // Prices are low and flat initially
        for (int i = 0; i < 15; i++) {
            history.add(new TradingService.AlpacaBar(100, 100, 100, 100));
        }
        // Price starts trending up, causing a crossover and high RSI
        history.add(new TradingService.AlpacaBar(101, 101, 101, 101));
        history.add(new TradingService.AlpacaBar(102, 102, 102, 102));
        history.add(new TradingService.AlpacaBar(103, 103, 103, 103));

        // Use the real bar history
        tradingService.getBarHistory().addAll(history);
        // Set the state to not be in a position
        tradingService.setInPosition(false);

        // Define what the "latest" bar will be when the method is called
        TradingService.AlpacaBar latestBar = new TradingService.AlpacaBar(104, 104, 104, 104);
        doReturn(latestBar).when(tradingService).fetchLatestBarFromAlpaca();

        // Act: Run the strategy
        tradingService.executeStrategy();

        // Assert: Verify that the buy method was called exactly once
        verify(tradingService, times(1)).buy(104.0);
        verify(tradingService, never()).sell(); // Ensure sell was not called
    }

    @Test
    void shouldSell_whenTakeProfitIsTriggered() {
        // Arrange: Set up the state for an open position
        tradingService.setInPosition(true);
        tradingService.setPurchasePrice(100.0);
        tradingService.setTakeProfitEnabled(true);
        tradingService.setTakeProfitPercentage(0.10); // 10% take profit

        // Populate enough history to pass the initial data gathering phase
        for (int i = 0; i < 15; i++) {
            tradingService.getBarHistory().add(new TradingService.AlpacaBar(100, 100, 100, 100));
        }

        // Define the latest bar to be a price that hits the 10% take-profit target ($110)
        TradingService.AlpacaBar latestBar = new TradingService.AlpacaBar(110, 110, 110, 110);
        doReturn(latestBar).when(tradingService).fetchLatestBarFromAlpaca();

        // Act: Run the strategy
        tradingService.executeStrategy();

        // Assert: Verify that the sell method was called because the take-profit was hit
        verify(tradingService, times(1)).sell();
        verify(tradingService, never()).buy(anyDouble()); // Ensure buy was not called
    }
}
*/
// NOTE: You may need to add a constructor to the AlpacaBar class for these tests to work.
// Inside TradingService.java, add this constructor to the AlpacaBar class:
/*
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaBar {
        @JsonProperty("c") private double close;
        @JsonProperty("h") private double high;
        @JsonProperty("l") private double low;
        @JsonProperty("o") private double open;

        // ADD THIS CONSTRUCTOR
        public AlpacaBar(double open, double high, double low, double close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }
*/
