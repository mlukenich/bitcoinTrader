package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.jacobpeterson.alpaca.AlpacaAPI;
// CORRECTED import statements for library version 10.0.1
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderSide;
import net.jacobpeterson.alpaca.model.endpoint.orders.enums.OrderTimeInForce;
import net.jacobpeterson.alpaca.model.util.apitype.enums.EndpointAPIType;
import net.jacobpeterson.alpaca.rest.exception.AlpacaAPIRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class TradingService {

    // --- Injected Configuration Values ---
    @Value("${alpaca.api.key}")
    private String apiKey;
    @Value("${alpaca.api.secret}")
    private String apiSecret;
    // The baseUrl is no longer needed as we use the EndpointAPIType enum.
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

    // --- State Variables ---
    private final List<Double> priceHistory = Collections.synchronizedList(new ArrayList<>());
    private boolean inPosition = false;
    private double purchasePrice = 0.0;
    private double previousShortMA = 0.0;
    private double previousLongMA = 0.0;
    private double lastKnownPrice = 0.0;

    private final RestTemplate restTemplate = new RestTemplate();
    private AlpacaAPI alpacaAPI;

    @PostConstruct
    public void init() {
        // CORRECTED INITIALIZATION: Using the EndpointAPIType enum for clarity and stability.
        this.alpacaAPI = new AlpacaAPI(apiKey, apiSecret, EndpointAPIType.PAPER);
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
            double quantity = tradeAmountUsd / currentPrice;
            alpacaAPI.orders().requestMarketOrder(
                    symbol,
                    quantity,
                    OrderSide.BUY,
                    OrderTimeInForce.GOOD_UNTIL_CANCELLED
            );
            System.out.println("BUY order placed successfully!");
            inPosition = true;
            purchasePrice = currentPrice;
        } catch (AlpacaAPIRequestException e) {
            System.err.println("Error placing BUY order: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sell() {
        System.out.printf("Attempting to place SELL order for all %s%n", symbol);
        try {
            alpacaAPI.positions().close(symbol);
            System.out.println("SELL order (close position) placed successfully!");
            inPosition = false;
            purchasePrice = 0.0;
        } catch (AlpacaAPIRequestException e) {
            System.err.println("Error placing SELL order: " + e.getMessage());
            if (!e.getMessage().contains("position not found")) {
                e.printStackTrace();
            } else {
                System.out.println("No position existed to sell. Resetting state.");
                inPosition = false;
                purchasePrice = 0.0;
            }
        }
    }

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


