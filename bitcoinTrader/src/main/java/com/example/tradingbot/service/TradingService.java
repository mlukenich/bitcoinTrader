package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
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

import com.example.tradingbot.model.BotState;
import com.example.tradingbot.repository.BotStateRepository;

import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingService {

    private final BotStateService botStateService;
    private final NotificationService notificationService;
    private final RestTemplate restTemplate;
    private static final Logger logger = LoggerFactory.getLogger(TradingService.class);
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
    @Value("${trading.bollinger.period}")
    private int bollingerPeriod;
    @Value("${trading.bollinger.stddev}")
    private double bollingerStdDev;

    // --- State Variables ---
    // Getters for UI data
    @Getter
    private double lastKnownPrice = 0.0;
    @Getter
    private double lastKnownRsi = 0.0;
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
    @Transactional // This is important for database operations
    public void init() {
        // 1. Set up authentication headers for all API calls
        this.apiHeaders = new HttpHeaders();
        this.apiHeaders.set("APCA-API-KEY-ID", apiKey);
        this.apiHeaders.set("APCA-API-SECRET-KEY", apiSecret);
        this.apiHeaders.setContentType(MediaType.APPLICATION_JSON);

        // 2. Load the bot's state from the database, or create it if it's the first run
        this.botState = botStateRepository.findById(1L).orElseGet(() -> {
            logger.info("No persistent state found. Creating new state in database.");
            return botStateRepository.save(new BotState());
        });

        logger.info("TradingService initialized. Bot is in position: {}", this.botState.isInPosition());

        // This is a good safety check to ensure our DB is in sync with Alpaca on startup
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
            botStateRepository.save(botState); // Save the updated price history
            return;
        }

        List<Double> currentPriceHistory = botState.getPriceHistory();

        botState.setLastKnownRsi(calculateRsi(currentPriceHistory));
        double shortMA = calculateMovingAverage(currentPriceHistory, shortMaPeriod);
        double longMA = calculateMovingAverage(currentPriceHistory, longMaPeriod);

        botStateService.setStatusMessage(String.format("Monitoring | RSI: %.2f", botState.getLastKnownRsi()));

        if (botState.isInPosition()) {
            botState.setHighestPriceSinceBuy(Math.max(botState.getHighestPriceSinceBuy(), currentPrice));

            double takeProfitPrice = botState.getPurchasePrice() * (1 + takeProfitPercentage);
            if (takeProfitEnabled && currentPrice >= takeProfitPrice) {
                String logMsg = String.format("Take-profit triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Take-Profit Executed", logMsg);
                sell();
                botState.setPreviousShortMA(shortMA);
                botState.setPreviousLongMA(longMA);
                return; // sell() already saves state
            }

            double trailingStopPrice = botState.getHighestPriceSinceBuy() * (1 - trailingStopPercentage);
            if (trailingStopEnabled && currentPrice <= trailingStopPrice) {
                String logMsg = String.format("Trailing stop-loss triggered at $%.2f (peak was $%.2f). Selling.", currentPrice, botState.getHighestPriceSinceBuy());
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Trailing Stop Executed", logMsg);
                sell();
                botState.setPreviousShortMA(shortMA);
                botState.setPreviousLongMA(longMA);
                return; // sell() already saves state
            }

            double initialStopPrice = botState.getPurchasePrice() * (1 - stopLossPercentage);
            if (currentPrice <= initialStopPrice) {
                String logMsg = String.format("Initial stop-loss triggered at $%.2f. Selling.", currentPrice);
                addLogEntry(logMsg);
                notificationService.sendTradeNotification("Trading Bot: Stop-Loss Executed", logMsg);
                sell();
                botState.setPreviousShortMA(shortMA);
                botState.setPreviousLongMA(longMA);
                return; // sell() already saves state
            }
        }

        boolean isBullish = botState.getLastKnownRsi() > 50;
        boolean isBearish = botState.getLastKnownRsi() < 50;

        if (shortMA > longMA && botState.getPreviousShortMA() <= botState.getPreviousLongMA() && !botState.isInPosition() && isBullish) {
            addLogEntry("MA Crossover and Bullish RSI detected. Placing BUY order.");
            buy(currentPrice);
        } else if (shortMA < longMA && botState.getPreviousShortMA() >= botState.getPreviousLongMA() && botState.isInPosition() && isBearish) {
            addLogEntry("MA Crossover and Bearish RSI detected. Placing SELL order.");
            sell();
        }

        botState.setPreviousShortMA(shortMA);
        botState.setPreviousLongMA(longMA);
        botStateRepository.save(botState); // Save state at the end of the check
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
            System.out.printf("Attempting to place BUY order for $%.2f%n", notionalAmount);

            String formattedNotional = String.format("%.2f", notionalAmount);
            OrderRequest orderRequest = new OrderRequest(symbol, null, formattedNotional, "buy", "market", "gtc");

            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(baseUrl + "/v2/orders", requestEntity, String.class);

            String message = String.format("BUY order placed for $%.2f of %s at price $%.2f", notionalAmount, symbol, currentPrice);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: BUY Order Executed", message);

            // Update the state object
            botState.setInPosition(true);
            botState.setPurchasePrice(currentPrice);
            botState.setHighestPriceSinceBuy(currentPrice);

        } catch (Exception e) {
            String errorMsg = "Error placing BUY order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState); // Persist changes
        }
    }

    @Transactional
    public void sell() {
        try {
            Position currentPosition = getPosition(symbol);
            if (currentPosition == null) {
                addLogEntry("SELL signal, but no position found on Alpaca. Resetting state.");
                botState.setInPosition(false);
                botState.setPurchasePrice(0.0);
                botState.setHighestPriceSinceBuy(0.0);
                return;
            }

            OrderRequest orderRequest = new OrderRequest(symbol, currentPosition.getQty(), null, "sell", "market", "gtc");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);
            restTemplate.postForObject(baseUrl + "/v2/orders", requestEntity, String.class);

            String message = String.format("SELL order placed for %s to close position.", symbol);
            addLogEntry(message);
            notificationService.sendTradeNotification("Trading Bot: SELL Order Executed", message);

            // Update the state object
            botState.setInPosition(false);
            botState.setPurchasePrice(0.0);
            botState.setHighestPriceSinceBuy(0.0);

        } catch (Exception e) {
            String errorMsg = "Error placing SELL order: " + e.getMessage();
            logger.error(errorMsg, e);
            addLogEntry(errorMsg);
        } finally {
            botStateRepository.save(botState); // Persist changes
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
            botState.setInPosition(false);
            botState.setPurchasePrice(0.0);
            botState.setHighestPriceSinceBuy(0.0);
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
        // Get the most recent 'period' prices from the list provided
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
        int chartHistorySize = 100; // How many data points to show on the chart
        List<Double> fullPriceHistory = this.botState.getPriceHistory(); // <-- Read from the state object

        if (fullPriceHistory.isEmpty()) {
            // Return empty lists if there's no history yet
            return Map.of("prices", List.of(), "shortMas", List.of(), "longMas", List.of());
        }

        // Get the subset of prices we want to display
        List<Double> pricesForChart = fullPriceHistory.subList(
                Math.max(0, fullPriceHistory.size() - chartHistorySize),
                fullPriceHistory.size()
        );

        List<Double> shortMasForChart = new ArrayList<>();
        List<Double> longMasForChart = new ArrayList<>();

        // Calculate the moving average values for each point that will be displayed
        for (int i = 0; i < pricesForChart.size(); i++) {
            // To calculate the MA at a given point, we need to look backwards into the full history
            int historyIndex = Math.max(0, fullPriceHistory.size() - pricesForChart.size()) + i;

            // Get the sublist needed for the short MA calculation at this point in time
            List<Double> subForShort = fullPriceHistory.subList(Math.max(0, historyIndex - shortMaPeriod + 1), historyIndex + 1);
            shortMasForChart.add(calculateMovingAverage(subForShort, shortMaPeriod));

            // Get the sublist needed for the long MA calculation at this point in time
            List<Double> subForLong = fullPriceHistory.subList(Math.max(0, historyIndex - longMaPeriod + 1), historyIndex + 1);
            longMasForChart.add(calculateMovingAverage(subForLong, longMaPeriod));
        }

        return Map.of(
                "prices", pricesForChart,
                "shortMas", shortMasForChart,
                "longMas", longMasForChart
        );
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
    public static class AlpacaBarsResponse {
        private Map<String, AlpacaBar> bars;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AlpacaBar {
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
