package com.example.tradingbot.service;

import com.example.tradingbot.config.AlpacaConfig;
import com.example.tradingbot.config.BotConfig;
import com.example.tradingbot.model.BotState;
import com.example.tradingbot.repository.BotStateRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TradingService {

    private static final Logger logger = LoggerFactory.getLogger(TradingService.class);
    private final BotStateService botStateService;
    private final NotificationService notificationService;
    private final RestTemplate restTemplate;
    private final BotStateRepository botStateRepository;
    private final AlpacaConfig alpacaConfig;
    private final BotConfig botConfig;
    private final WebSocketService webSocketService;

    @Getter
    private final List<LogEntry> activityLog = new CopyOnWriteArrayList<>();
    private HttpHeaders apiHeaders;
    private BotState botState;

    public TradingService(BotStateService botStateService, NotificationService notificationService, RestTemplate restTemplate, BotStateRepository botStateRepository, AlpacaConfig alpacaConfig, BotConfig botConfig, WebSocketService webSocketService) {
        this.botStateService = botStateService;
        this.notificationService = notificationService;
        this.restTemplate = restTemplate;
        this.botStateRepository = botStateRepository;
        this.alpacaConfig = alpacaConfig;
        this.botConfig = botConfig;
        this.webSocketService = webSocketService;
    }

    @PostConstruct
    @Transactional
    public void init() {
        this.apiHeaders = new HttpHeaders();
        this.apiHeaders.set("APCA-API-KEY-ID", alpacaConfig.getApiKey());
        this.apiHeaders.set("APCA-API-SECRET-KEY", alpacaConfig.getApiSecret());
        this.apiHeaders.setContentType(MediaType.APPLICATION_JSON);

        this.botState = getBotState();
        addLogEntry("Trading bot started.");
        logger.info("TradingService initialized. Bot is in position: {}", this.botState.isInPosition());
        synchronizePositionState();
        broadcastFullUpdate();
    }

    @PreDestroy
    public void shutdown() {
        addLogEntry("Trading bot stopped.");
        logger.info("Trading bot is shutting down.");
        broadcastFullUpdate();
    }

    private BotState getBotState() {
        return botStateRepository.findById(1L).orElseGet(() -> {
            logger.info("No persistent state found. Creating new state in database.");
            return botStateRepository.save(new BotState());
        });
    }

    @Transactional
    public void executeStrategy() {
        AlpacaBar latestBar = fetchLatestBarFromAlpaca();
        if (latestBar == null) {
            botStateService.setStatusMessage("Could not fetch price data...");
            broadcastFullUpdate();
            return;
        }

        double currentPrice = latestBar.getClose();
        botState.setLastKnownPrice(currentPrice);
        botState.getPriceHistory().add(currentPrice);

        int maxHistorySize = botConfig.getLongMaPeriod() + 100;
        while (botState.getPriceHistory().size() > maxHistorySize) {
            botState.getPriceHistory().remove(0);
            if (!botState.getShortMaHistory().isEmpty()) botState.getShortMaHistory().remove(0);
            if (!botState.getLongMaHistory().isEmpty()) botState.getLongMaHistory().remove(0);
        }

        if (botState.getPriceHistory().size() < botConfig.getRsiPeriod() + 1) {
            String message = String.format("Gathering Price Data (%d/%d)", botState.getPriceHistory().size(), botConfig.getRsiPeriod() + 1);
            botStateService.setStatusMessage(message);
            botStateRepository.save(botState);
            broadcastFullUpdate();
            return;
        }

        List<Double> currentPriceHistory = botState.getPriceHistory();

        botState.setLastKnownRsi(calculateRsi(currentPriceHistory));
        double shortMA = calculateMovingAverage(currentPriceHistory, botConfig.getShortMaPeriod());
        double longMA = calculateMovingAverage(currentPriceHistory, botConfig.getLongMaPeriod());

        botState.getShortMaHistory().add(shortMA);
        botState.getLongMaHistory().add(longMA);

        botStateService.setStatusMessage(String.format("Monitoring | RSI: %.2f", botState.getLastKnownRsi()));

        if (botState.isInPosition()) {
            handleInPositionLogic(currentPrice, shortMA, longMA);
        } else {
            handleNotInPositionLogic(currentPrice, shortMA, longMA);
        }

        botState.setPreviousShortMA(shortMA);
        botState.setPreviousLongMA(longMA);
        botStateRepository.save(botState);
        broadcastFullUpdate();
    }

    private void handleInPositionLogic(double currentPrice, double shortMA, double longMA) {
        botState.setHighestPriceSinceBuy(Math.max(botState.getHighestPriceSinceBuy(), currentPrice));

        boolean isBearish = botState.getLastKnownRsi() < 50;
        if (shortMA < longMA && botState.getPreviousShortMA() >= botState.getPreviousLongMA() && isBearish) {
            sellAndLog("MA Crossover and Bearish RSI detected. Placing SELL order.");
        }
    }

    private void handleNotInPositionLogic(double currentPrice, double shortMA, double longMA) {
        boolean isBullish = botState.getLastKnownRsi() > 50;
        if (shortMA > longMA && botState.getPreviousShortMA() <= botState.getPreviousLongMA() && isBullish) {
            buy(currentPrice, "MA Crossover and Bullish RSI detected");
        }
    }

    private void sellAndLog(String reason, Object... args) {
        String logMsg = String.format(reason, args);
        addLogEntry(logMsg);
        notificationService.sendTradeNotification("Trading Bot: Trade Executed", logMsg);
        sell();
    }

    @Transactional
    public void buy(double currentPrice, String reason) {
        try {
            AlpacaAccount account = getAccountStatus();
            if (account == null) {
                addLogEntry("Could not fetch account equity. Skipping buy order.");
                return;
            }
            double notionalAmount = account.getEquity() * botConfig.getRiskPercentage();
            if (notionalAmount < 1.0) {
                addLogEntry(String.format("Insufficient funds to place buy order. Required > $1.00, but calculated notional is only $%.2f", notionalAmount));
                return;
            }
            OrderRequest orderRequest = new OrderRequest(botConfig.getSymbol(), null, String.format("%.2f", notionalAmount), "buy", "market", "gtc");

            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(alpacaConfig.getBaseUrl() + "/v2/orders", requestEntity, String.class);

            String message = String.format("%s. BUY order placed for $%.2f of %s at price $%.2f", reason, notionalAmount, botConfig.getSymbol(), currentPrice);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: BUY Order Executed", message);

            botState.setInPosition(true);
            botState.setPurchasePrice(currentPrice);
            botState.setHighestPriceSinceBuy(currentPrice);

        } catch (HttpClientErrorException e) {
            String errorMsg = "API Error placing BUY order: " + e.getResponseBodyAsString();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } catch (Exception e) {
            String errorMsg = "Unexpected error placing BUY order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState);
            broadcastFullUpdate();
        }
    }

    @Transactional
    public void sell() {
        try {
            Position currentPosition = getPosition(botConfig.getSymbol());
            if (currentPosition == null) {
                addLogEntry("SELL signal, but no position found on Alpaca. Resetting state.");
                resetPositionState();
                return;
            }

            OrderRequest orderRequest = new OrderRequest(botConfig.getSymbol(), currentPosition.getQty(), null, "sell", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(alpacaConfig.getBaseUrl() + "/v2/orders", requestEntity, String.class);

            String message = String.format("SELL order placed for %s to close position.", botConfig.getSymbol());
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: SELL Order Executed", message);

            resetPositionState();

        } catch (HttpClientErrorException e) {
            String errorMsg = "API Error placing SELL order: " + e.getResponseBodyAsString();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } catch (Exception e) {
            String errorMsg = "Unexpected error placing SELL order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState);
            broadcastFullUpdate();
        }
    }

    private void synchronizePositionState() {
        Position existingPosition = getPosition(botConfig.getSymbol());
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
        String url = alpacaConfig.getBaseUrl() + "/v2/account";
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<AlpacaAccount> response = restTemplate.exchange(url, HttpMethod.GET, entity, AlpacaAccount.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            logger.error("API Error getting account status: {}", e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error getting account status: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getBtcPositionPl() {
        if (!botState.isInPosition()) {
            return Map.of("unrealizedPl", 0.0, "isPositive", true);
        }
        Position position = getPosition(botConfig.getSymbol());
        if (position != null && position.getUnrealizedPl() != null) {
            double unrealizedPl = Double.parseDouble(position.getUnrealizedPl());
            return Map.of("unrealizedPl", unrealizedPl, "isPositive", unrealizedPl >= 0);
        }
        return Map.of("unrealizedPl", 0.0, "isPositive", true);
    }

    private Position getPosition(String symbol) {
        String encodedSymbol = symbol.replace("/", "%2F");
        String url = alpacaConfig.getBaseUrl() + "/v2/positions/" + encodedSymbol;
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        try {
            ResponseEntity<Position> response = restTemplate.exchange(url, HttpMethod.GET, entity, Position.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404) {
                return null;
            }
            logger.error("API Error getting position for {}: {}", symbol, e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error getting position for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    public AlpacaBar fetchLatestBarFromAlpaca() {
        try {
            String url = alpacaConfig.getDataUrl() + "/v1beta3/crypto/us/latest/bars?symbols=" + botConfig.getSymbol();
            HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
            ResponseEntity<AlpacaBarsResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, AlpacaBarsResponse.class);
            if (response.getBody() != null && response.getBody().getBars() != null &&
                    response.getBody().getBars().containsKey(botConfig.getSymbol())) {
                return response.getBody().getBars().get(botConfig.getSymbol());
            }
        } catch (HttpClientErrorException e) {
            logger.error("API Error fetching latest bar: {}", e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Unexpected error fetching latest bar: {}", e.getMessage());
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
        if (prices == null || prices.size() < botConfig.getRsiPeriod() + 1) {
            return 0.0;
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        for (int i = prices.size() - botConfig.getRsiPeriod(); i < prices.size(); i++) {
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
        String timestamp = LocalDateTime.now().toString();
        activityLog.add(0, new LogEntry(timestamp, message));
        while (activityLog.size() > 20) {
            activityLog.remove(activityLog.size() - 1);
        }
        broadcastFullUpdate();
    }

    public Map<String, Object> getFullUpdate() {
        Map<String, Object> update = new HashMap<>();

        String status = botStateService.getStatus();
        update.put("running", status != null && (status.equalsIgnoreCase("RUNNING") || status.equalsIgnoreCase("STARTED")));
        update.put("statusMessage", status != null ? status : "STOPPED");

        Map<String, Object> positionMap = new HashMap<>();
        if (botState.isInPosition()) {
            positionMap.put("positionType", "BUY");
            positionMap.put("entryPrice", botState.getPurchasePrice());
        } else {
            positionMap.put("positionType", "None");
            positionMap.put("entryPrice", 0.0);
        }
        update.put("position", positionMap);

        update.put("currentPrice", getLastKnownPrice());
        update.put("lastKnownRsi", getLastKnownRsi());
        update.put("chartData", getChartData());

        AlpacaAccount account = getAccountStatus();
        update.put("equity", (account != null) ? account.getEquity() : 0.0);

        Map<String, Object> plData = getBtcPositionPl();
        update.put("unrealizedPL", plData.getOrDefault("unrealizedPl", 0.0));

        update.put("logs", getActivityLog());

        return update;
    }

    private void broadcastFullUpdate() {
        webSocketService.sendUpdate(getFullUpdate());
    }

    public Map<String, List<?>> getChartData() {
        int chartHistorySize = 100;
        List<Double> prices = botState.getPriceHistory();
        List<Double> shortMas = botState.getShortMaHistory();
        List<Double> longMas = botState.getLongMaHistory();

        return Map.of(
                "prices", prices.subList(Math.max(0, prices.size() - chartHistorySize), prices.size()),
                "shortMas", shortMas.subList(Math.max(0, shortMas.size() - chartHistorySize), shortMas.size()),
                "longMas", longMas.subList(Math.max(0, longMas.size() - chartHistorySize), longMas.size())
        );
    }

    public double getLastKnownPrice() {
        return botState != null ? botState.getLastKnownPrice() : 0.0;
    }

    public double getLastKnownRsi() {
        return botState != null ? botState.getLastKnownRsi() : 0.0;
    }

    @Data
    @AllArgsConstructor
    public static class LogEntry {
        private String timestamp;
        private String message;
    }

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
