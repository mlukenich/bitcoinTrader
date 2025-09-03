import os
import pandas as pd
import yfinance as yf
from datetime import date, timedelta

# --- Configuration ---
TICKER = "BTC-USD"  # The Yahoo Finance ticker for Bitcoin
INTERVAL = "1h"     # We want hourly data for our higher-frequency bot
DATA_DIR = "data/raw"

# yfinance allows fetching a maximum of 730 days of hourly data in one go
START_DATE = date.today() - timedelta(days = 500)
END_DATE = date.today()

def download_btc_data():
    """
    Downloads historical hourly data for Bitcoin using yfinance
    and saves it to a CSV file.
    """
    print(f"--- Starting {INTERVAL} Data Download for {TICKER} ---")

    # Ensure the data directory exists
    if not os.path.exists(DATA_DIR):
        os.makedirs(DATA_DIR)

    try:
        print(f"Fetching data from {START_DATE} to {END_DATE}...")
        df = yf.download(
            tickers=TICKER,
            start=START_DATE,
            end=END_DATE,
            interval=INTERVAL
        )

        if not df.empty:
            file_path = os.path.join(DATA_DIR, f"{TICKER}.csv")
            df.to_csv(file_path)
            print(f"Successfully saved {len(df)} rows of data to {file_path}")
        else:
            print(f"No data returned for {TICKER}.")

    except Exception as e:
        print(f"Could not fetch data for {TICKER}: {e}")

    print("--- Data Download Complete ---")

if __name__ == '__main__':
    download_btc_data()