package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
// --- IMPORTS FOR THE NEW 'net.jacobpeterson' LIBRARY ---
import net.jacobpeterson.alpaca.Alpaca;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderSide;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderTimeInForce;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderType;
import net.jacobpeterson.alpaca.model.endpoint.orders.request.MarketOrderRequest;
import net.jacobpeterson.alpaca.model.endpoint.positions.Position;
import net.jacobpeterson.alpaca.rest.AlpacaClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class TradingService {

    // --- Injected Configuration Values (Unchanged) ---
    @Value("${alpaca.api.key}")
    private String apiKey;
    @Value("${alpaca.api.secret}")
    private String apiSecret;
    @Value("${alpaca.api.base-url}")
    private String baseUrl; // This URL determines paper or live trading
    @Value("${trading.symbol}")
    private String symbol;
    @Value("${trading.short-ma-period}")
    private int shortMaPeriod;
    @Value("${trading.long-ma-period}")
    private int longMaPeriod;
    @Value("${trading.trade-amount-usd}")
    private double tradeAmountUsd;
    @Value("${trading.stop-loss-percentage}")
    private double stopLossPercentage;

    // --- State Variables (Unchanged) ---
    private final List<Double> priceHistory = Collections.synchronizedList(new ArrayList<>());
    private boolean inPosition = false;
    private double purchasePrice = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;
    private double lastKnownPrice = 0.0;

    private final RestTemplate restTemplate = new RestTemplate();
    
    // --- UPDATED: The new Alpaca client object ---
    private Alpaca alpaca;

    @PostConstruct
    public void init() {
        // --- UPDATED: Initialization of the new Alpaca client ---
        // Note: The new library handles endpoint selection (paper/live) via the URL.
        // Make sure your baseUrl is correct (e.g., https://paper-api.alpaca.markets)
        this.alpaca = new Alpaca(apiKey, apiSecret, baseUrl);
    }

    public void executeStrategy() {
        double currentPrice = fetchCurrentBitcoinPrice();
        if (currentPrice == 0.0 && lastKnownPrice != 0.0) {
            currentPrice = lastKnownPrice;
        } else if (currentPrice == 0.0) { return; }

        priceHistory.add(currentPrice);
        lastKnownPrice = currentPrice;

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

    private void buy(double currentPrice) {
        System.out.printf("Attempting to place BUY order for $%.2f of %s%n", tradeAmountUsd, symbol);
        try {
            // --- UPDATED: Building a market order request with the new library ---
            MarketOrderRequest marketOrderRequest = new MarketOrderRequest(
                    symbol,
                    null, // Quantity can be null when using notional value
                    OrderSide.BUY,
                    OrderType.MARKET,
                    OrderTimeInForce.DAY, // GTC (Good 'til Canceled) is often not supported for crypto
                    null,
                    null,
                    tradeAmountUsd, // Use 'notional' for dollar amount buys
                    null
            );

            alpaca.orders().requestMarketOrder(marketOrderRequest);
            System.out.println("BUY order placed successfully!");
            inPosition = true;
            purchasePrice = currentPrice;
        } catch (AlpacaClientException e) {
            System.err.println("Error placing BUY order: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sell() {
        System.out.printf("Attempting to place SELL order for all %s%n", symbol);
        try {
            // --- UPDATED: Getting the position and creating a SELL order ---
            Position position = alpaca.positions().getOpenPositionBySymbol(symbol);
            String quantityToSell = position.getQty(); // The quantity is now a String

            MarketOrderRequest marketOrderRequest = new MarketOrderRequest(
                    symbol,
                    quantityToSell, // Provide the exact quantity to sell
                    OrderSide.SELL,
                    OrderType.MARKET,
                    OrderTimeInForce.DAY,
                    null, null, null, null
            );
            
            alpaca.orders().requestMarketOrder(marketOrderRequest);
            System.out.println("SELL order (to close position) placed successfully!");
            inPosition = false;
            purchasePrice = 0.0;
        } catch (AlpacaClientException e) {
            System.err.println("Error placing SELL order: " + e.getMessage());
            // This library throws a specific exception when a position is not found
            if (e.getAPIResponse() != null && e.getAPIResponse().getCode() == 40410000) {
                 System.out.println("No position existed to sell. Resetting state.");
                 inPosition = false;
                 purchasePrice = 0.0;
            } else {
                e.printStackTrace();
            }
        }
    }

    // --- HELPER METHODS (Unchanged) ---

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
            if (response != null && response.getData() != null) {
                return Double.parseDouble(response.getData().getPriceUsd());
            }
        } catch (Exception e) {
            System.err.println("Error fetching Bitcoin price: " + e.getMessage());
        }
        return 0.0;
    }

    // --- DTO CLASSES (Unchanged) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CoinCapResponse {
        private AssetData data;
        public AssetData getData() { return data; }
        public void setData(AssetData data) { this.data = data; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AssetData {
        private String priceUsd;
        @JsonProperty("priceUsd")
        public String getPriceUsd() { return priceUsd; }
        public void setPriceUsd(String priceUsd) { this.priceUsd = priceUsd; }
    }
}


