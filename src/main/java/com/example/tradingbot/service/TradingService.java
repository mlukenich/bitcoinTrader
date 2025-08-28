package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import lombok.Setter;
import lombok.Getter;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Service
public class TradingService {

    // --- Injected Configuration Values ---
    @Value("${alpaca.api.key}")
    private String apiKey;

    @Value("${alpaca.api.secret}")
    private String apiSecret;

    @Value("${alpaca.api.base-url}")
    private String baseUrl;

    @Value("${alpaca.api.data-url}")
    private String dataUrl;

    @Value("${trading.symbol}")
    private String symbol;

    @Setter
    @Value("${trading.short-ma-period}")
    private int shortMaPeriod;

    @Setter
    @Value("${trading.long-ma-period}")
    private int longMaPeriod;

    @Value("${trading.risk.percentage}")
    private double riskPercentage;

    @Value("${trading.initial-stop-loss.percentage}")
    private double initialStopLossPercentage;

    @Value("${trading.atr.period}")
    private int atrPeriod;

    @Value("${trading.atr.multiplier}")
    private double atrMultiplier;

    @Setter
    @Value("${trading.rsi-period}")
    private int rsiPeriod;

    @Setter
    @Value("${trading.take-profit.enabled}")
    private boolean takeProfitEnabled;

    @Setter
    @Value("${trading.take-profit.percentage}")
    private double takeProfitPercentage;

    @Value("${trading.trailing-stop.enabled}")
    private boolean trailingStopEnabled;

    @Value("${trading.trailing-stop.percentage}")
    private double trailingStopPercentage;

    // --- State Variables ---
    private final List<Double> priceHistory = Collections.synchronizedList(new ArrayList<>());
    @Setter
    private boolean inPosition = false;
    @Setter
    private double purchasePrice = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;
    private double lastKnownPrice = 0.0;
    private final List<String> activityLog = new CopyOnWriteArrayList<>();
    private double lastKnownRsi = 0.0;
    private double highestPriceSinceBuy = 0.0;
    @Getter
    private final List<AlpacaBar> barHistory = Collections.synchronizedList(new ArrayList<>());

    private final RestTemplate restTemplate = new RestTemplate();
    private HttpHeaders apiHeaders;

    private final BotStateService botStateService;
    private final NotificationService notificationService;

    public TradingService(BotStateService botStateService, NotificationService notificationService) {
        this.botStateService = botStateService;
        this.notificationService = notificationService;
    }

    /**
     * This method runs after the service is created. It initializes the API headers
     * and synchronizes the bot's state with the actual Alpaca account.
     */
    @PostConstruct
    public void init() {
        // Create reusable headers for all API calls
        this.apiHeaders = new HttpHeaders();
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);
        this.apiHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Synchronize state on startup
        synchronizePositionState();
    }

    /**
     * The main trading logic loop.
     */
    public void executeStrategy() {
        // This method now returns a full AlpacaBar object
        AlpacaBar latestBar = fetchLatestBarFromAlpaca();

        if (latestBar == null && !barHistory.isEmpty()) {
            latestBar = barHistory.get(barHistory.size() - 1); // Use last known bar on failure
        } else if (latestBar == null) {
            botStateService.setStatusMessage("Could not fetch price data...");
            return;
        }

        double currentPrice = latestBar.getClose();
        lastKnownPrice = currentPrice;
        barHistory.add(latestBar);

        // Trim history to prevent memory leaks
        while (barHistory.size() > longMaPeriod + 1) { // Keep a little extra for calculations
            barHistory.remove(0);
        }

        // We need enough bars for the longest calculation (either long MA or ATR period)
        int requiredDataPoints = Math.max(longMaPeriod, atrPeriod) + 1;
        if (barHistory.size() < requiredDataPoints) {
            String message = String.format("Gathering Price Data (%d/%d)", barHistory.size(), requiredDataPoints);
            botStateService.setStatusMessage(message);
            return;
        }

        // Extract close prices for MA and RSI calculations
        List<Double> closePrices = barHistory.stream().map(AlpacaBar::getClose).collect(Collectors.toList());

        this.lastKnownRsi = calculateRsi(closePrices);
        double shortMA = calculateMovingAverage(closePrices, shortMaPeriod);
        double longMA = calculateMovingAverage(closePrices, longMaPeriod);

        botStateService.setStatusMessage(String.format("Monitoring | RSI: %.2f", this.lastKnownRsi));

        if (inPosition) {
            // Update the highest price seen since the buy to be used by the trailing stop
            highestPriceSinceBuy = Math.max(highestPriceSinceBuy, currentPrice);

            // --- Exit Condition Priority ---
            // 1. Check for Take-Profit condition first to lock in gains.
            double takeProfitPrice = purchasePrice * (1 + takeProfitPercentage);
            if (takeProfitEnabled && currentPrice >= takeProfitPrice) {
                String logMsg = String.format("Take-profit triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Take-Profit Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }

            // 2. Check for Trailing Stop-Loss to protect profits.
            double trailingStopPrice = highestPriceSinceBuy * (1 - trailingStopPercentage);
            if (trailingStopEnabled && currentPrice <= trailingStopPrice) {
                String logMsg = String.format("Trailing stop-loss triggered at $%.2f (peak was $%.2f). Selling.", currentPrice, highestPriceSinceBuy);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Trailing Stop Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }

            // 3. Check for ATR-based stop-loss as the primary risk management tool.
            double atr = calculateAtr();
            double atrStopPrice = purchasePrice - (atr * atrMultiplier);
            if (atr > 0 && currentPrice <= atrStopPrice) {
                String logMsg = String.format("ATR stop-loss triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: ATR Stop Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }
        }

        // --- Entry and Indicator-Based Exit Logic ---
        boolean isBullish = this.lastKnownRsi > 50;
        boolean isBearish = this.lastKnownRsi < 50;

        if (shortMA > longMA && previousShortMA <= previousLongMA && !inPosition && isBullish) {
            addLogEntry("MA Crossover and Bullish RSI detected. Placing BUY order.");
            buy(currentPrice);
        } else if (shortMA < longMA && previousShortMA >= previousLongMA && inPosition && isBearish) {
            addLogEntry("MA Crossover and Bearish RSI detected. Placing SELL order.");
            sell();
        }

        resetMovingAverages(shortMA, longMA);
    }

    private double calculateRsi(List<Double> prices) {
        if (prices.size() < rsiPeriod + 1) {
            return 0.0;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        // Calculate gains and losses for the last `rsiPeriod` changes
        for (int i = priceHistory.size() - rsiPeriod; i < priceHistory.size(); i++) {
            double change = priceHistory.get(i) - priceHistory.get(i - 1);
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }

        // Calculate average gain and loss
        double avgGain = gains.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(d -> d).average().orElse(0.0);

        if (avgLoss == 0) {
            return 100.0; // Prevent division by zero; max bullishness
        }

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * Places a BUY order using a direct POST request to the Alpaca API.
     */
    private void buy(double currentPrice) {
        try {
            // Step 1: Fetch current account equity before trading
            AlpacaAccount account = getAccountStatus();
            if (account == null) {
                String errorMsg = "Could not fetch account equity. Skipping buy order.";
                System.err.println(errorMsg);
                addLogEntry(errorMsg);
                return;
            }

            // Step 2: Calculate the trade size in dollars based on risk percentage
            double notionalAmount = account.getEquity() * riskPercentage;

            System.out.printf("Attempting to place BUY order for $%.2f (%.2f%% of equity)%n", notionalAmount, riskPercentage * 100);

            // Step 3: Place the order using the dynamically calculated amount
            String url = baseUrl + "/v2/orders";
            OrderRequest orderRequest = new OrderRequest(symbol, null, String.valueOf(notionalAmount), "buy", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);

            restTemplate.postForObject(url, requestEntity, String.class);

            // Step 4: Update logs and notifications with the dynamic amount
            System.out.println("BUY order placed successfully!");
            String message = String.format("BUY order placed for $%.2f of %s at price $%.2f", notionalAmount, symbol, currentPrice);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: BUY Order Executed", message);

            inPosition = true;
            purchasePrice = currentPrice;
            highestPriceSinceBuy = currentPrice;
        } catch (Exception e) {
            String errorMsg = "Error placing BUY order: " + e.getMessage();
            System.err.println(errorMsg);
            addLogEntry(errorMsg);
        }
    }

    /**
     * Places a SELL order by first getting the position quantity, then posting a sell order.
     */
    public void sell() {
        System.out.printf("Attempting to place SELL order for all %s%n", symbol);
        try {
            Position currentPosition = getPosition(symbol);
            if (currentPosition == null) {
                System.out.println("No position existed to sell. Resetting state.");
                addLogEntry("SELL signal, but no position found. Resetting state.");
                String message = String.format("SELL order placed for %s to close position.", symbol);
                notificationService.sendTradeNotification("Trading Bot: SELL Order Executed", message);
                inPosition = false;
                purchasePrice = 0.0;
                highestPriceSinceBuy = 0.0;
                return;
            }

            String quantityToSell = currentPosition.getQty();

            String url = baseUrl + "/v2/orders";
            OrderRequest orderRequest = new OrderRequest(symbol, quantityToSell, null, "sell", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);

            restTemplate.postForObject(url, requestEntity, String.class);
            System.out.println("SELL order (to close position) placed successfully!");
            addLogEntry(String.format("SELL order placed for %s to close position.", symbol));
            inPosition = false;
            purchasePrice = 0.0;
            highestPriceSinceBuy = 0.0;
        } catch (Exception e) {
            String errorMsg = "Error placing SELL order: " + e.getMessage();
            System.err.println(errorMsg);
            addLogEntry(errorMsg);
        }
    }

    /**
     * Fetches crypto price from Alpaca's Data API.
     */
    // --- NEW METHOD: Fetches crypto price from Alpaca's Data API ---
    private double fetchCurrentBitcoinPriceFromAlpaca() {
        try {
            // FINAL FIX: Use the original symbol directly in the URL.
            // RestTemplate is smart enough to handle this correctly.
            String url = dataUrl + "/v1beta3/crypto/us/latest/bars?symbols=" + symbol;

            HttpEntity<String> entity = new HttpEntity<>(apiHeaders);

            ResponseEntity<AlpacaBarsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AlpacaBarsResponse.class);

            // Use the original symbol (with the slash) to look up the result in the map
            if (response.getBody() != null && response.getBody().getBars() != null &&
                    response.getBody().getBars().containsKey(symbol)) {

                AlpacaBar bar = response.getBody().getBars().get(symbol);
                return bar.getClose();
            }
        } catch (Exception e) {
            System.err.println("Error fetching Bitcoin price from Alpaca: " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * A helper method to get a single position by its symbol from the Alpaca API.
     */
    private Position getPosition(String symbol) {
        String encodedSymbol = symbol.replace("/", "%2F");
        String url = baseUrl + "/v2/positions/" + encodedSymbol;
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);

        try {
            ResponseEntity<Position> response = restTemplate.exchange(url, HttpMethod.GET, entity, Position.class);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            return null; // This is the expected case when no position exists.
        } catch (Exception e) {
            System.err.println("Error fetching position for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks Alpaca for an open position for the given symbol on startup.
     */
    private void synchronizePositionState() {
        System.out.println("Synchronizing state with Alpaca account...");
        Position existingPosition = getPosition(symbol);

        if (existingPosition != null) {
            this.inPosition = true;
            this.purchasePrice = Double.parseDouble(existingPosition.getAvgEntryPrice());
            this.highestPriceSinceBuy = this.purchasePrice;
            System.out.printf("Found existing position for %s. Quantity: %s, Avg Entry Price: $%.2f%n",
                    symbol, existingPosition.getQty(), this.purchasePrice);
        } else {
            this.inPosition = false;
            this.purchasePrice = 0.0;
            this.highestPriceSinceBuy = 0.0;
            System.out.println("No existing position found for " + symbol + ". Initializing fresh state.");
        }
    }

    public AlpacaAccount getAccountStatus() {
        String url = baseUrl + "/v2/account";
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<AlpacaAccount> response = restTemplate.exchange(url, HttpMethod.GET, entity, AlpacaAccount.class);
            return response.getBody();
        } catch (Exception e) {
            System.err.println("Error fetching Alpaca account info: " + e.getMessage());
            return null;
        }
    }

    public AlpacaBar fetchLatestBarFromAlpaca() {
        try {
            String url = dataUrl + "/v1beta3/crypto/us/latest/bars?symbols=" + symbol;
            HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
            ResponseEntity<AlpacaBarsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AlpacaBarsResponse.class);

            if (response.getBody() != null && response.getBody().getBars() != null &&
                    response.getBody().getBars().containsKey(symbol)) {
                return response.getBody().getBars().get(symbol);
            }
        } catch (Exception e) {
            System.err.println("Error fetching latest bar from Alpaca: " + e.getMessage());
        }
        return null;
    }

    private double calculateAtr() {
        if (barHistory.size() < atrPeriod + 1) {
            return 0.0; // Not enough data
        }

        List<Double> trueRanges = new ArrayList<>();
        // Start from the second-to-last bar to compare with the one before it
        for (int i = barHistory.size() - atrPeriod; i < barHistory.size(); i++) {
            AlpacaBar currentBar = barHistory.get(i);
            AlpacaBar previousBar = barHistory.get(i - 1);

            double highMinusLow = currentBar.getHigh() - currentBar.getLow();
            double highMinusPrevClose = Math.abs(currentBar.getHigh() - previousBar.getClose());
            double lowMinusPrevClose = Math.abs(currentBar.getLow() - previousBar.getClose());

            double trueRange = Math.max(highMinusLow, Math.max(highMinusPrevClose, lowMinusPrevClose));
            trueRanges.add(trueRange);
        }

        // Return the average of the true ranges
        return trueRanges.stream().mapToDouble(d -> d).average().orElse(0.0);
    }



    private void resetMovingAverages(double shortMA, double longMA) {
        this.previousShortMA = shortMA;
        this.previousLongMA = longMA;
    }

    private double calculateMovingAverage(List<Double> prices, int period) {
        if (prices.size() < period) return 0.0;
        List<Double> recentPrices = prices.subList(prices.size() - period, prices.size());
        return recentPrices.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private void addLogEntry(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        activityLog.add(0, String.format("[%s] %s", timestamp, message)); // Add to the top of the list
        // Keep the log from growing indefinitely
        while (activityLog.size() > 20) {
            activityLog.remove(activityLog.size() - 1);
        }
    }

    public List<String> getActivityLog() {
        return this.activityLog;
    }

    public double getLastKnownPrice() {
        return lastKnownPrice;
    }
    public double getLastKnownRsi() {
        return this.lastKnownRsi;
    }
    // --- Helper Classes for JSON data handling ---

    @Data
    @NoArgsConstructor
    private static class OrderRequest {
        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getQty() {
            return qty;
        }

        public void setQty(String qty) {
            this.qty = qty;
        }

        public String getNotional() {
            return notional;
        }

        public void setNotional(String notional) {
            this.notional = notional;
        }

        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getTimeInForce() {
            return timeInForce;
        }

        public void setTimeInForce(String timeInForce) {
            this.timeInForce = timeInForce;
        }

        private String symbol;
        private String qty;
        private String notional;
        private String side;
        private String type;
        @JsonProperty("time_in_force")
        private String timeInForce;

        public OrderRequest(String symbol, String qty, String notional, String side, String type, String timeInForce) {
            this.symbol = symbol;
            this.qty = qty;
            this.notional = notional;
            this.side = side;
            this.type = type;
            this.timeInForce = timeInForce;
        }


    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Position {
        private String symbol;
        private String qty;
        @JsonProperty("avg_entry_price")
        private String avgEntryPrice;

        public String getSymbol() {
            return symbol;
        }

        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        public String getQty() {
            return qty;
        }

        public void setQty(String qty) {
            this.qty = qty;
        }

        public String getAvgEntryPrice() {
            return avgEntryPrice;
        }

        public void setAvgEntryPrice(String avgEntryPrice) {
            this.avgEntryPrice = avgEntryPrice;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaBarsResponse {
        private Map<String, AlpacaBar> bars;

        public Map<String, AlpacaBar> getBars() {
            return bars;
        }

        public void setBars(Map<String, AlpacaBar> bars) {
            this.bars = bars;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaBar {
        @JsonProperty("c")
        private double close;
        @JsonProperty("h")
        private double high;
        @JsonProperty("l")
        private double low;
        @JsonProperty("o")
        private double open;

        public double getClose() {
            return close;
        }

        public void setClose(double close) {
            this.close = close;
        }

        public double getHigh() {
            return high;
        }

        public void setHigh(double high) {
            this.high = high;
        }

        public double getLow() {
            return low;
        }

        public void setLow(double low) {
            this.low = low;
        }

        public double getOpen() {
            return open;
        }

        public void setOpen(double open) {
            this.open = open;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaAccount {
        private String status;
        private double equity;
        @JsonProperty("last_equity")
        private double lastEquity;
        @JsonProperty("buying_power")
        private double buyingPower;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public double getEquity() {
            return equity;
        }

        public void setEquity(double equity) {
            this.equity = equity;
        }

        public double getLastEquity() {
            return lastEquity;
        }

        public void setLastEquity(double lastEquity) {
            this.lastEquity = lastEquity;
        }

        public double getBuyingPower() {
            return buyingPower;
        }

        public void setBuyingPower(double buyingPower) {
            this.buyingPower = buyingPower;
        }
    }
}