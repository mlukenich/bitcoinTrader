package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class TradingService {

    // --- Injected Configuration Values ---
    @Value("${alpaca.api.key}")
    private String apiKey;
    @Value("${alpaca.api.secret}")
    private String apiSecret;
    @Value("${alpaca.api.base-url}")
    private String baseUrl; // e.g., https://paper-api.alpaca.markets
    @Value("${trading.symbol}")
    private String symbol; // e.g., BTC/USD
    @Value("${trading.short-ma-period}")
    private int shortMaPeriod;
    @Value("${trading.long-ma-period}")
    private int longMaPeriod;
    @Value("${trading.trade-amount-usd}")
    private double tradeAmountUsd;
    @Value("${trading.stop-loss-percentage}")
    private double stopLossPercentage;

    // --- State Variables ---
    private final List<Double> priceHistory = Collections.synchronizedList(new ArrayList<>());
    private boolean inPosition = false;
    private double purchasePrice = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;
    private double lastKnownPrice = 0.0;

    private final RestTemplate restTemplate = new RestTemplate();
    private HttpHeaders apiHeaders;

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
        double currentPrice = fetchCurrentBitcoinPrice();
        if (currentPrice == 0.0 && lastKnownPrice != 0.0) {
            currentPrice = lastKnownPrice;
        } else if (currentPrice == 0.0) { return; }

        priceHistory.add(currentPrice);
        lastKnownPrice = currentPrice;

        // Trim the price history to prevent memory leaks
        while (priceHistory.size() > longMaPeriod) {
            priceHistory.remove(0);
        }

        if (priceHistory.size() < longMaPeriod) { return; }

        double shortMA = calculateMovingAverage(shortMaPeriod);
        double longMA = calculateMovingAverage(longMaPeriod);

        if (inPosition) {
            double stopLossPrice = purchasePrice * (1 - stopLossPercentage);
            if (currentPrice <= stopLossPrice) {
                sell();
                resetMovingAverages(shortMA, longMA);
                return;
            }
        }

        if (shortMA > longMA && previousShortMA <= previousLongMA && !inPosition) {
            buy(currentPrice);
        } else if (shortMA < longMA && previousShortMA >= previousLongMA && inPosition) {
            sell();
        }

        resetMovingAverages(shortMA, longMA);
    }

    /**
     * Places a BUY order using a direct POST request to the Alpaca API.
     */
    private void buy(double currentPrice) {
        System.out.printf("Attempting to place BUY order for $%.2f of %s%n", tradeAmountUsd, symbol);
        String url = baseUrl + "/v2/orders";
        
        // Create the JSON request body
        OrderRequest orderRequest = new OrderRequest(symbol, String.valueOf(tradeAmountUsd), "buy", "market", "day");
        HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);

        try {
            restTemplate.postForObject(url, requestEntity, String.class);
            System.out.println("BUY order placed successfully!");
            inPosition = true;
            purchasePrice = currentPrice;
        } catch (Exception e) {
            System.err.println("Error placing BUY order: " + e.getMessage());
        }
    }

    /**
     * Places a SELL order by first getting the position quantity, then posting a sell order.
     */
    private void sell() {
        System.out.printf("Attempting to place SELL order for all %s%n", symbol);
        try {
            // Step 1: Get the current position from Alpaca to find the exact quantity to sell.
            Position currentPosition = getPosition(symbol);
            if (currentPosition == null) {
                System.out.println("No position existed to sell. Resetting state.");
                inPosition = false;
                purchasePrice = 0.0;
                return;
            }

            String quantityToSell = currentPosition.getQty();

            // Step 2: Submit a SELL order for the entire quantity.
            String url = baseUrl + "/v2/orders";
            OrderRequest orderRequest = new OrderRequest(symbol, quantityToSell, "sell", "market", "day");
            HttpEntity<OrderRequest> requestEntity = new HttpEntity<>(orderRequest, apiHeaders);

            restTemplate.postForObject(url, requestEntity, String.class);
            System.out.println("SELL order (to close position) placed successfully!");
            inPosition = false;
            purchasePrice = 0.0;

        } catch (Exception e) {
            System.err.println("Error placing SELL order: " + e.getMessage());
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
            System.out.printf("Found existing position for %s. Quantity: %s, Avg Entry Price: $%.2f%n",
                    symbol, existingPosition.getQty(), this.purchasePrice);
        } else {
            this.inPosition = false;
            this.purchasePrice = 0.0;
            System.out.println("No existing position found for " + symbol + ". Initializing fresh state.");
        }
    }
    
    /**
     * A helper method to get a single position by its symbol from the Alpaca API.
     */
    private Position getPosition(String symbol) {
        // Alpaca API requires URL encoding for symbols with a '/'
        String encodedSymbol = symbol.replace("/", "%2F");
        String url = baseUrl + "/v2/positions/" + encodedSymbol;
        HttpEntity<String> entity = new HttpEntity<>(apiHeaders);
        
        try {
            ResponseEntity<Position> response = restTemplate.exchange(url, HttpMethod.GET, entity, Position.class);
            return response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            // This is the expected error when no position exists.
            return null;
        } catch (Exception e) {
            System.err.println("Error fetching position for " + symbol + ": " + e.getMessage());
            return null;
        }
    }
    
    // --- Helper Methods (Unchanged) ---

    private void resetMovingAverages(double shortMA, double longMA) {
        this.previousShortMA = shortMA;
        this.previousLongMA = longMA;
    }

    private double calculateMovingAverage(int period) {
        if (priceHistory.size() < period) return 0.0;
        List<Double> recentPrices = priceHistory.subList(priceHistory.size() - period, priceHistory.size());
        return recentPrices.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double fetchCurrentBitcoinPrice() {
        final String url = "https://api.coincap.io/v2/assets/bitcoin";
        try {
            CoinCapResponse response = restTemplate.getForObject(url, CoinCapResponse.class);
            if (response != null && response.getData() != null && response.getData().getPriceUsd() != null) {
                return Double.parseDouble(response.getData().getPriceUsd());
            }
        } catch (Exception e) {
            System.err.println("Error fetching Bitcoin price: " + e.getMessage());
        }
        return 0.0;
    }
    
    // --- Helper Classes for JSON data handling ---

    // Used for creating the JSON body of a new order request.
    @Data
    @NoArgsConstructor
    private static class OrderRequest {
        private String symbol;
        private String notional; // For BUY orders in USD
        private String qty;      // For SELL orders
        private String side;
        private String type;
        @JsonProperty("time_in_force")
        private String timeInForce;

        // Constructor for a notional (dollar-based) BUY order
        public OrderRequest(String symbol, String notional, String side, String type, String timeInForce) {
            this.symbol = symbol;
            this.notional = notional;
            this.side = side;
            this.type = type;
            this.timeInForce = timeInForce;
        }
    }

    // Used for parsing the JSON response when fetching an existing position.
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Position {
        private String symbol;
        private String qty;
        @JsonProperty("avg_entry_price")
        private String avgEntryPrice;
    }

    // DTOs for CoinCap Price Fetching (Unchanged)
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CoinCapResponse {
        private AssetData data;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AssetData {
        private String priceUsd;
    }
}