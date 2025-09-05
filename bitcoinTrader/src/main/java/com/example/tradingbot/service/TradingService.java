package com.example.tradingbot.service;

import com.example.tradingbot.model.BotState;
import com.example.tradingbot.repository.BotStateRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core service for handling trading logic, API interactions, and state management.
 * This service is responsible for executing the trading strategy, managing the bot's state,
 * and interacting with the Alpaca API.
 */
@Service
public class TradingService {

    private static final Logger logger = LoggerFactory.getLogger(TradingService.class);
    private final BotStateService botStateService;
    private final NotificationService notificationService;
    private final RestTemplate restTemplate;
    private final BotStateRepository botStateRepository;

    // --- Injected Configuration Values ---
    @Value("${ALPCA_API_KEY:${alpaca.api.key}}")
    private String apiKey;
    @Value("${ALPCA_API_SECRET:${alpaca.api.secret}}")
    private String apiSecret;
    @Value("${alpaca.api.base-url}")
    private String baseUrl;
    @Value("${alpaca.api.data-url}")
    private String dataUrl;
    @Setter
    @Value("${trading.symbol}")
    private String symbol;
    @Setter
    @Value("${trading.short-ma-period}")
    private int shortMaPeriod;
    @Setter
    @Value("${trading.long-ma-period}")
    private int longMaPeriod;
    @Setter
    @Value("${trading.rsi-period}")
    private int rsiPeriod;
    @Setter
    @Value("${trading.risk.percentage}")
    private double riskPercentage;
    @Value("${trading.initial-stop-loss.percentage}")
    private double stopLossPercentage;
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
    @Getter
    private final List<String> activityLog = new CopyOnWriteArrayList<>();
    private HttpHeaders apiHeaders;
    private BotState botState;

    public TradingService(BotStateService botStateService, NotificationService notificationService, RestTemplate restTemplate, BotStateRepository botStateRepository) {
        this.botStateService = botStateService;
        this.notificationService = notificationService;
        this.restTemplate = restTemplate;
        this.botStateRepository = botStateRepository;
    }

    @PostConstruct
    @Transactional
    public void init() {
        this.apiHeaders = new HttpHeaders();
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);
        this.apiHeaders.setContentType(MediaType.APPLICATION_JSON);

        this.botState = botStateRepository.findById(1L).orElseGet(() -> {
            logger.info("No persistent state found. Creating new state in database.");
            return botStateRepository.save(new BotState());
        });

        logger.info("TradingService initialized. Bot is in position: {}", this.botState.isInPosition());
        synchronizePositionState();
    }

    @Transactional
    public void executeStrategy() {
        AlpacaBar latestBar = fetchLatestBarFromAlpaca();
        if (latestBar == null) {
            botStateService.setStatusMessage("Could not fetch price data...");
            return;
        }

        double currentPrice = latestBar.getClose();
        botState.setLastKnownPrice(currentPrice);
        botState.getPriceHistory().add(currentPrice);

        while (botState.getPriceHistory().size() > longMaPeriod + 1) {
            botState.getPriceHistory().remove(0);
        }

        if (botState.getPriceHistory().size() < rsiPeriod + 1) {
            String message = String.format("Gathering Price Data (%d/%d)", botState.getPriceHistory().size(), rsiPeriod + 1);
            botStateService.setStatusMessage(message);
            botStateRepository.save(botState);
            return;
        }

        List<Double> currentPriceHistory = botState.getPriceHistory();

        botState.setLastKnownRsi(calculateRsi(currentPriceHistory));
        double shortMA = calculateMovingAverage(currentPriceHistory, shortMaPeriod);
        double longMA = calculateMovingAverage(currentPriceHistory, longMaPeriod);

        botStateService.setStatusMessage(String.format("Monitoring | RSI: %.2f", botState.getLastKnownRsi()));

        if (botState.isInPosition()) {
            handleInPositionLogic(currentPrice, shortMA, longMA);
        } else {
            handleNotInPositionLogic(currentPrice, shortMA, longMA);
        }

        botState.setPreviousShortMA(shortMA);
        botState.setPreviousLongMA(longMA);
        botStateRepository.save(botState);
    }

    private void handleInPositionLogic(double currentPrice, double shortMA, double longMA) {
        botState.setHighestPriceSinceBuy(Math.max(botState.getHighestPriceSinceBuy(), currentPrice));

        double takeProfitPrice = botState.getPurchasePrice() * (1 + takeProfitPercentage);
        if (takeProfitEnabled && currentPrice >= takeProfitPrice) {
            sellAndLog("Take-profit triggered at $%.2f. Selling.", currentPrice);
            return;
        }

        double trailingStopPrice = botState.getHighestPriceSinceBuy() * (1 - trailingStopPercentage);
        if (trailingStopEnabled && currentPrice <= trailingStopPrice) {
            sellAndLog("Trailing stop-loss triggered at $%.2f (peak was $%.2f). Selling.", currentPrice, botState.getHighestPriceSinceBuy());
            return;
        }

        double initialStopPrice = botState.getPurchasePrice() * (1 - stopLossPercentage);
        if (currentPrice <= initialStopPrice) {
            sellAndLog("Initial stop-loss triggered at $%.2f. Selling.", currentPrice);
            return;
        }

        boolean isBearish = botState.getLastKnownRsi() < 50;
        if (shortMA < longMA && botState.getPreviousShortMA() >= botState.getPreviousLongMA() && isBearish) {
            sellAndLog("MA Crossover and Bearish RSI detected. Placing SELL order.");
        }
    }

    private void handleNotInPositionLogic(double currentPrice, double shortMA, double longMA) {
        boolean isBullish = botState.getLastKnownRsi() > 50;
        if (shortMA > longMA && botState.getPreviousShortMA() <= botState.getPreviousLongMA() && isBullish) {
            addLogEntry("MA Crossover and Bullish RSI detected. Placing BUY order.");
            buy(currentPrice);
        }
    }

    private void sellAndLog(String reason, Object... args) {
        String logMsg = String.format(reason, args);
        addLogEntry(logMsg);
        notificationService.sendTradeNotification("Trading Bot: Trade Executed", logMsg);
        sell();
    }

    @Transactional
    public void buy(double currentPrice) {
        try {
            AlpacaAccount account = getAccountStatus();
            if (account == null) {
                addLogEntry("Could not fetch account equity. Skipping buy order.");
                return;
            }
            double notionalAmount = account.getEquity() * riskPercentage;
            OrderRequest orderRequest = new OrderRequest(symbol, null, String.format("%.2f", notionalAmount), "buy", "market", "gtc");

            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(baseUrl + "/v2/orders", requestEntity, String.class);

            String message = String.format("BUY order placed for $%.2f of %s at price $%.2f", notionalAmount, symbol, currentPrice);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: BUY Order Executed", message);

            botState.setInPosition(true);
            botState.setPurchasePrice(currentPrice);
            botState.setHighestPriceSinceBuy(currentPrice);

        } catch (Exception e) {
            String errorMsg = "Error placing BUY order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState);
        }
    }

    @Transactional
    public void sell() {
        try {
            Position currentPosition = getPosition(symbol);
            if (currentPosition == null) {
                addLogEntry("SELL signal, but no position found on Alpaca. Resetting state.");
                resetPositionState();
                return;
            }

            OrderRequest orderRequest = new OrderRequest(symbol, currentPosition.getQty(), null, "sell", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(baseUrl + "/v2/orders", requestEntity, String.class);

            String message = String.format("SELL order placed for %s to close position.", symbol);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: SELL Order Executed", message);

            resetPositionState();

        } catch (Exception e) {
            String errorMsg = "Error placing SELL order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState);
        }
    }

    private void synchronizePositionState() {
        Position existingPosition = getPosition(symbol);
        if (existingPosition != null) {
            botState.setInPosition(true);
            double avgEntryPrice = Double.parseDouble(existingPosition.getAvgEntryPrice());
            botState.setPurchasePrice(avgEntryPrice);
            botState.setHighestPriceSinceBuy(avgEntryPrice);
        } else {
            resetPositionState();
        }
    }

    private void resetPositionState() {
        botState.setInPosition(false);
        botState.setPurchasePrice(0.0);
        botState.setHighestPriceSinceBuy(0.0);
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
        if (!botState.isInPosition()) {
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
        } catch (Exception e) {
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

    public double calculateMovingAverage(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) {
            return 0.0;
        }
        List<Double> recentPrices = prices.subList(prices.size() - period, prices.size());
        return recentPrices.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    public double calculateRsi(List<Double> prices) {
        if (prices == null || prices.size() < rsiPeriod + 1) {
            return 0.0;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        for (int i = prices.size() - rsiPeriod; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
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

        if (avgLoss == 0) {
            return 100.0;
        }

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

    public Map<String, List<?>> getChartData() {
        int chartHistorySize = 100;
        List<Double> fullPriceHistory = this.botState.getPriceHistory();

        if (fullPriceHistory.isEmpty()) {
            return Map.of("prices", List.of(), "shortMas", List.of(), "longMas", List.of());
        }

        List<Double> pricesForChart = fullPriceHistory.subList(
                Math.max(0, fullPriceHistory.size() - chartHistorySize),
                fullPriceHistory.size()
        );

        List<Double> shortMasForChart = new ArrayList<>();
        List<Double> longMasForChart = new ArrayList<>();

        for (int i = 0; i < pricesForChart.size(); i++) {
            int historyIndex = Math.max(0, fullPriceHistory.size() - pricesForChart.size()) + i;

            List<Double> subForShort = fullPriceHistory.subList(Math.max(0, historyIndex - shortMaPeriod + 1), historyIndex + 1);
            shortMasForChart.add(calculateMovingAverage(subForShort, shortMaPeriod));

            List<Double> subForLong = fullPriceHistory.subList(Math.max(0, historyIndex - longMaPeriod + 1), historyIndex + 1);
            longMasForChart.add(calculateMovingAverage(subForLong, longMaPeriod));
        }

        return Map.of(
                "prices", pricesForChart,
                "shortMas", shortMasForChart,
                "longMas", longMasForChart
        );
    }

    public double getLastKnownPrice() {
        return botState != null ? botState.getLastKnownPrice() : 0.0;
    }

    public double getLastKnownRsi() {
        return botState != null ? botState.getLastKnownRsi() : 0.0;
    }

    // --- Helper Classes for JSON ---
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderRequest {
        private String symbol;
        private String qty;
        private String notional;
        private String side;
        private String type;
        @JsonProperty("time_in_force")
        private String timeInForce;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Position {
        private String symbol;
        private String qty;
        @JsonProperty("avg_entry_price")
        private String avgEntryPrice;
        @JsonProperty("unrealized_pl")
        private String unrealizedPl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaBarsResponse {
        private Map<String, AlpacaBar> bars;
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
    }
}