import os
import pandas as pd
import pandas_ta as ta

# --- Configuration ---
TICKER = "BTC-USD"
RAW_DATA_DIR = "data/raw"
PROCESSED_DIR = "data/processed"

# --- Labeling Configuration (for hourly data) ---
FORWARD_WINDOW = 24  # Look forward 24 hours
PROFIT_TARGET = 0.02  # 2% profit target
STOP_LOSS = 0.01  # 1% stop-loss

def process_btc_data():
    """
    Loads raw hourly data, calculates base features, creates lagged features,
    creates a target label, and saves the processed data.
    """
    print(f"--- Starting Data Processing for {TICKER} ---")

    if not os.path.exists(PROCESSED_DIR):
        os.makedirs(PROCESSED_DIR)

    file_path = os.path.join(RAW_DATA_DIR, f"{TICKER}.csv")
    if not os.path.exists(file_path):
        print(f"Error: Raw data file not found at {file_path}")
        return

    # --- THIS IS THE CORRECTED LINE ---
    # It now includes skiprows=[1] to skip the junk "Ticker" row.
    df = pd.read_csv(file_path, header=0, index_col=0, skiprows=[1], parse_dates=True)
    df.index.name = 'Date'
    print(f"Loaded {len(df)} rows of raw data.")

    # --- 1. Feature Engineering - Base Indicators ---
    print("Calculating base indicators...")
    df.ta.sma(length=20, append=True)
    df.ta.sma(length=100, append=True)
    df.ta.rsi(length=14, append=True)
    df.ta.macd(append=True)
    df.ta.bbands(length=20, append=True)
    df.ta.atr(length=14, append=True)
    df.ta.adx(length=14, append=True)
    df.ta.obv(append=True)

    # --- 2. Feature Engineering - Lagged Features ---
    print("Creating lagged features...")
    indicators_to_lag = ['RSI_14', 'MACDh_12_26_9', 'BBP_20_2.0', 'OBV']
    lag_periods = [1, 3, 6, 12]

    for indicator in indicators_to_lag:
        for lag in lag_periods:
            df[f'{indicator}_lag_{lag}'] = df[indicator].shift(lag)

    # --- 3. Labeling ---
    print("Creating target labels...")
    df['target'] = 0
    for i in range(len(df) - FORWARD_WINDOW):
        entry_price = df['Close'].iloc[i]
        profit_price = entry_price * (1 + PROFIT_TARGET)
        loss_price = entry_price * (1 - STOP_LOSS)

        for j in range(1, FORWARD_WINDOW + 1):
            future_price = df['Close'].iloc[i + j]
            if future_price >= profit_price:
                df.at[df.index[i], 'target'] = 1
                break
            if future_price <= loss_price:
                break

    # --- 4. Clean and Save ---
    df.dropna(inplace=True)
    print(f"Data cleaned. Final dataset has {len(df)} rows.")

    processed_file_path = os.path.join(PROCESSED_DIR, f"{TICKER}_processed.csv")
    df.to_csv(processed_file_path)
    print(f"Successfully processed and saved data to {processed_file_path}")
    print("--- Data Processing Complete ---")

if __name__ == '__main__':
    process_btc_data()