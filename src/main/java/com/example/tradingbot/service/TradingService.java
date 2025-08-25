package com.example.tradingbot.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
// CORRECTED IMPORTS for the 'io.github.mainstringargs' library
import io.github.mainstringargs.alpaca.AlpacaAPI;
import io.github.mainstringargs.alpaca.enums.OrderSide;
import io.github.mainstringargs.alpaca.enums.OrderTimeInForce;
import io.github.mainstringargs.alpaca.rest.exception.AlpacaAPIRequestException;
import io.github.mainstringargs.domain.alpaca.position.Position;
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
    @Value("${alpaca.api.base-url}")
    private String baseUrl;
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
        this.alpacaAPI = new AlpacaAPI(apiKey, apiSecret, baseUrl);
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
            // This library uses a direct method call for market orders and requires an integer quantity
            alpacaAPI.requestNewMarketOrder(symbol, (int) Math.floor(quantity), OrderSide.BUY, OrderTimeInForce.GTC, null, null);
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
            // Get the current position to find out the exact quantity to sell.
            Position position = alpacaAPI.getPosition(symbol);
            int quantityToSell = position.getQty();

            // Submit a SELL order for the entire quantity.
            alpacaAPI.requestNewMarketOrder(symbol, quantityToSell, OrderSide.SELL, OrderTimeInForce.GTC, null, null);
            System.out.println("SELL order (to close position) placed successfully!");
            inPosition = false;
            purchasePrice = 0.0;
        } catch (AlpacaAPIRequestException e) {
            System.err.println("Error placing SELL order: " + e.getMessage());
            if (e.getMessage().contains("position not found")) {
                System.out.println("No position existed to sell. Resetting state.");
                inPosition = false;
                purchasePrice = 0.0;
            } else {
                e.printStackTrace();
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


