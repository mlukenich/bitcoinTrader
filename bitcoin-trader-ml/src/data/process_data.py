import os
import pandas as pd
import pandas_ta as ta

# --- Configuration ---
TICKER = "BTC-USD"
RAW_DATA_DIR = "data/raw"
PROCESSED_DIR = "data/processed" # Let's create a new subfolder

# --- Labeling Configuration (for hourly data) ---
FORWARD_WINDOW = 24  # Look forward 24 hours
PROFIT_TARGET = 0.02  # 2% profit target
STOP_LOSS = 0.01  # 1% stop-loss

def process_btc_data():
    """
    Loads raw hourly data, calculates features (indicators), creates a
    target label, and saves the processed data to a new CSV file.
    """
    print(f"--- Starting Data Processing for {TICKER} ---")

    # Ensure the output directory exists
    if not os.path.exists(PROCESSED_DIR):
        os.makedirs(PROCESSED_DIR)

    file_path = os.path.join(RAW_DATA_DIR, f"{TICKER}.csv")
    if not os.path.exists(file_path):
        print(f"Error: Raw data file not found at {file_path}")
        return

    # Load the raw data
    df = pd.read_csv(file_path, header=0, index_col=0, skiprows=[1], parse_dates=True)
    df.index.name = 'Date'

    # --- 1. Feature Engineering ---
    # Use longer periods suitable for hourly data
    df.ta.sma(length=20, append=True)
    df.ta.sma(length=100, append=True)
    df.ta.rsi(length=14, append=True)
    df.ta.macd(append=True)
    df.ta.bbands(length=20, append=True) # 20-hour Bollinger Bands
    df.ta.atr(length=14, append=True)
    df.ta.adx(length=14, append=True)
    df.ta.obv(append=True)

    # --- 2. Labeling ---
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

    # --- 3. Clean and Save ---
    df.dropna(inplace=True)

    processed_file_path = os.path.join(PROCESSED_DIR, f"{TICKER}_processed.csv")
    df.to_csv(processed_file_path)
    print(f"Successfully processed and saved data to {processed_file_path}")
    print("--- Data Processing Complete ---")

if __name__ == '__main__':
    process_btc_data()