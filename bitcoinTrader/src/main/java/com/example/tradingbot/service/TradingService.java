package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

@Service
public class TradingService {

    private final BotStateService botStateService;
    private final NotificationService notificationService;
    private final RestTemplate restTemplate;
    private static final Logger logger = LoggerFactory.getLogger(TradingService.class);


    // --- Injected Configuration Values ---
    @Value("${ALPCA_API_KEY:${alpaca.api.key}}")
    private String apiKey;
    @Value("${ALPCA_API_SECRET:${alpaca.api.secret}}")
    private String apiSecret;
    @Value("${alpaca.api.base-url}")
    private String baseUrl;
    @Value("${alpaca.api.data-url}")
    private String dataUrl;
    @Value("${trading.symbol}")
    private String symbol;
    @Value("${trading.short-ma-period}")
    private int shortMaPeriod;
    @Value("${trading.long-ma-period}")
    private int longMaPeriod;
    @Value("${trading.rsi-period}")
    private int rsiPeriod;
    @Setter
    @Value("${trading.risk.percentage}")
    private double riskPercentage;
    @Value("${trading.initial-stop-loss.percentage}")
    private double stopLossPercentage;
    @Value("${trading.take-profit.enabled}")
    private boolean takeProfitEnabled;
    @Value("${trading.take-profit.percentage}")
    private double takeProfitPercentage;
    @Value("${trading.trailing-stop.enabled}")
    private boolean trailingStopEnabled;
    @Value("${trading.trailing-stop.percentage}")
    private double trailingStopPercentage;
    @Value("${bot.state.filepath}")
    private String stateFilepath;

    // --- State Variables ---
    private final List<Double> priceHistory = Collections.synchronizedList(new ArrayList<>());
    private boolean inPosition = false;
    private double purchasePrice = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;
    // Getters for UI data
    @Getter
    private double lastKnownPrice = 0.0;
    @Getter
    private double lastKnownRsi = 0.0;
    @Getter
    private final List<String> activityLog = new CopyOnWriteArrayList<>();
    private double highestPriceSinceBuy = 0.0;
    private HttpHeaders apiHeaders;

    public TradingService(BotStateService botStateService, NotificationService notificationService, RestTemplate restTemplate) {
        this.botStateService = botStateService;
        this.notificationService = notificationService;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    public void loadStateOnStartup() {
        // First, set up the API headers
        this.apiHeaders = new HttpHeaders();
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);
        this.apiHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Next, try to load the price history from the file
        File stateFile = new File(stateFilepath);
        if (stateFile.exists()) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                String json = new String(Files.readAllBytes(Paths.get(stateFilepath)));
                // De-serialize the JSON from the file into our list
                this.priceHistory.addAll(objectMapper.readValue(json, new TypeReference<List<Double>>() {}));

                if (!priceHistory.isEmpty()) {
                    this.lastKnownPrice = priceHistory.get(priceHistory.size() - 1);
                }
                logger.info("Successfully loaded {} price history records from {}", priceHistory.size(), stateFilepath);
            } catch (Exception e) {
                logger.error("Could not load price history from file, starting fresh.", e);
            }
        } else {
            logger.info("No state file found. Starting with a fresh price history.");
        }

        // If we have enough historical data from the file, perform an initial
        // calculation to populate the indicators immediately on startup.
        if (this.priceHistory.size() >= rsiPeriod + 1) {
            logger.info("Performing initial indicator calculation from loaded history...");
            this.lastKnownRsi = calculateRsi();
        }

        // Finally, synchronize the position state with Alpaca
        synchronizePositionState();
    }

    @PreDestroy
    public void saveStateOnShutdown() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            // Serialize the priceHistory list to a JSON string
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.priceHistory);

            // Ensure the directory exists
            File stateFile = new File(stateFilepath);
            stateFile.getParentFile().mkdirs();

            // Write the JSON string to the file
            Files.writeString(Paths.get(stateFilepath), json);
            logger.info("Successfully saved {} price history records to {}", priceHistory.size(), stateFilepath);
        } catch (Exception e) {
            logger.error("Could not save price history to file.", e);
        }
    }

    public void executeStrategy() {
        AlpacaBar latestBar = fetchLatestBarFromAlpaca();
        if (latestBar == null && lastKnownPrice > 0) {
            // On a temporary failure, use the last known price to avoid stopping the logic
        } else if (latestBar == null) {
            botStateService.setStatusMessage("Could not fetch price data...");
            return;
        }

        double currentPrice = (latestBar != null) ? latestBar.getClose() : this.lastKnownPrice;
        this.lastKnownPrice = currentPrice;
        priceHistory.add(currentPrice);

        while (priceHistory.size() > longMaPeriod + 1) {
            priceHistory.remove(0);
        }

        if (priceHistory.size() < rsiPeriod + 1) {
            String message = String.format("Gathering Price Data (%d/%d)", priceHistory.size(), rsiPeriod + 1);
            botStateService.setStatusMessage(message);
            return;
        }

        this.lastKnownRsi = calculateRsi();
        double shortMA = calculateMovingAverage(shortMaPeriod);
        double longMA = calculateMovingAverage(longMaPeriod);

        botStateService.setStatusMessage(String.format("Monitoring | RSI: %.2f", this.lastKnownRsi));

        if (inPosition) {
            highestPriceSinceBuy = Math.max(highestPriceSinceBuy, currentPrice);

            double takeProfitPrice = purchasePrice * (1 + takeProfitPercentage);
            if (takeProfitEnabled && currentPrice >= takeProfitPrice) {
                String logMsg = String.format("Take-profit triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Take-Profit Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }

            double trailingStopPrice = highestPriceSinceBuy * (1 - trailingStopPercentage);
            if (trailingStopEnabled && currentPrice <= trailingStopPrice) {
                String logMsg = String.format("Trailing stop-loss triggered at $%.2f (peak was $%.2f). Selling.", currentPrice, highestPriceSinceBuy);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Trailing Stop Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }

            double initialStopPrice = purchasePrice * (1 - stopLossPercentage);
            if (currentPrice <= initialStopPrice) {
                String logMsg = String.format("Initial stop-loss triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Stop-Loss Executed", logMsg);
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }
        }

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

    public void buy(double currentPrice) {
        try {
            AlpacaAccount account = getAccountStatus();
            if (account == null) {
                addLogEntry("Could not fetch account equity. Skipping buy order.");
                return;
            }
            double notionalAmount = account.getEquity() * riskPercentage;
            System.out.printf("Attempting to place BUY order for $%.2f%n", notionalAmount);

            String url = baseUrl + "/v2/orders";
            String formattedNotional = String.format("%.2f", notionalAmount);
            OrderRequest orderRequest = new OrderRequest(symbol, null, formattedNotional, "buy", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(url, requestEntity, String.class);

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

    private void sell() {
        try {
            Position currentPosition = getPosition(symbol);
            if (currentPosition == null) {
                addLogEntry("SELL signal, but no position found on Alpaca. Resetting state.");
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

            System.out.println("SELL order placed successfully!");
            String message = String.format("SELL order placed for %s to close position.", symbol);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: SELL Order Executed", message);
            inPosition = false;
            purchasePrice = 0.0;
            highestPriceSinceBuy = 0.0;
        } catch (Exception e) {
            String errorMsg = "Error placing SELL order: " + e.getMessage();
            System.err.println(errorMsg);
            addLogEntry(errorMsg);
        }
    }

    private void synchronizePositionState() {
        Position existingPosition = getPosition(symbol);
        if (existingPosition != null) {
            this.inPosition = true;
            this.purchasePrice = Double.parseDouble(existingPosition.getAvgEntryPrice());
            this.highestPriceSinceBuy = this.purchasePrice;
        } else {
            this.inPosition = false;
            this.purchasePrice = 0.0;
            this.highestPriceSinceBuy = 0.0;
        }
    }

    public AlpacaAccount getAccountStatus() {
        String url = baseUrl + "/v2/account";
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<AlpacaAccount> response = restTemplate.exchange(url, HttpMethod.GET, entity, AlpacaAccount.class);
            return response.getBody();
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> getBtcPositionPl() {
        if (!inPosition) {
            return Map.of("unrealizedPl", 0.0, "isPositive", true);
        }
        Position position = getPosition(symbol);
        if (position != null && position.getUnrealizedPl() != null) {
            double unrealizedPl = Double.parseDouble(position.getUnrealizedPl());
            return Map.of("unrealizedPl", unrealizedPl, "isPositive", unrealizedPl >= 0);
        }
        return Map.of("unrealizedPl", 0.0, "isPositive", true);
    }

    private Position getPosition(String symbol) {
        String encodedSymbol = symbol.replace("/", "%2F");
        String url = baseUrl + "/v2/positions/" + encodedSymbol;
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<Position> response = restTemplate.exchange(url, HttpMethod.GET, entity, Position.class);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private AlpacaBar fetchLatestBarFromAlpaca() {
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

    private double calculateMovingAverage(int period) {
        if (priceHistory.size() < period) return 0.0;
        List<Double> recentPrices = priceHistory.subList(priceHistory.size() - period, priceHistory.size());
        return recentPrices.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double calculateRsi() {
        if (priceHistory.size() < rsiPeriod + 1) return 0.0;
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
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
        double avgGain = gains.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(d -> d).average().orElse(0.0);
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    public void addLogEntry(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        activityLog.add(0, String.format("[%s] %s", timestamp, message));
        while (activityLog.size() > 20) {
            activityLog.remove(activityLog.size() - 1);
        }
    }

    private void resetMovingAverages(double shortMA, double longMA) {
        this.previousShortMA = shortMA;
        this.previousLongMA = longMA;
    }

    // --- Helper Classes for JSON ---
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class OrderRequest {
        private String symbol;
        private String qty;
        private String notional;
        private String side;
        private String type;
        @JsonProperty("time_in_force")
        private String timeInForce;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Position {
        private String symbol;
        private String qty;
        @JsonProperty("avg_entry_price")
        private String avgEntryPrice;
        @JsonProperty("unrealized_pl")
        private String unrealizedPl;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaBarsResponse {
        private Map<String, AlpacaBar> bars;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AlpacaBar {
        @Getter
        @Setter
        @JsonProperty("c") private double close;
        @JsonProperty("h") private double high;
        @JsonProperty("l") private double low;
        @JsonProperty("o") private double open;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaAccount {
        private String status;
        private double equity;
        @JsonProperty("last_equity")
        private double lastEquity;
        @JsonProperty("buying_power")
        private double buyingPower;
    }
}