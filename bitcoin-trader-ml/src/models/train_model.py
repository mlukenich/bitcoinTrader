import os
import pandas as pd
import joblib
import numpy as np # Import numpy
from sklearn.model_selection import train_test_split
from xgboost import XGBClassifier
from sklearn.metrics import classification_report
from imblearn.over_sampling import SMOTE
import matplotlib.pyplot as plt

#### THIS SHIT IS BROKE, BUG FIX IN GOOGLE COLLABS####
# --- Configuration ---
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
PROCESSED_DIR = os.path.join(PROJECT_ROOT, "data", "processed")
MODEL_DIR = os.path.join(PROJECT_ROOT, "models")
TICKER = "BTC-USD"

def train_model():
    """
    Simplified training script to debug the fit process.
    """
    print(f"--- Starting Model Training for {TICKER} (Debug Mode) ---")

    # --- 1. Load Data ---
    file_path = os.path.join(PROCESSED_DIR, f"{TICKER}_processed.csv")
    df = pd.read_csv(file_path, index_col='Date', parse_dates=True)

    # --- EXTRA DATA SANITIZATION ---
    # Replace any infinite values with NaN and then drop those rows
    df.replace([np.inf, -np.inf], np.nan, inplace=True)
    df.dropna(inplace=True)
    print(f"Loaded and sanitized {len(df)} rows of processed data.")

    # --- 2. Define Features (X) and Target (y) ---
    adx_cols = [col for col in df.columns if 'ADX' in col]
    bbands_cols = [col for col in df.columns if 'BB' in col]
    macd_cols = [col for col in df.columns if 'MACD' in col]
    lag_cols = [col for col in df.columns if 'lag' in col]
    feature_cols = ['SMA_20', 'SMA_100', 'RSI_14', 'ATRr_14', 'OBV'] + adx_cols + bbands_cols + macd_cols + lag_cols

    X = df[feature_cols]
    y = df['target']

    # --- 3. Split Data ---
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, shuffle=False
    )

    # --- 4. Apply SMOTE ---
    print("\nApplying SMOTE to balance the training data...")
    smote = SMOTE(random_state=42)
    X_train_resampled, y_train_resampled = smote.fit_resample(X_train, y_train)

    # --- 5. Train a SINGLE XGBoost Model (No GridSearchCV) ---
    print("\nAttempting to train a single XGBClassifier...")
    model = XGBClassifier(random_state=42, use_label_encoder=False, eval_metric='logloss')

    try:
        model.fit(X_train_resampled, y_train_resampled)
        print("Model training complete.")
    except Exception as e:
        print("\n--- ERROR DURING FIT ---")
        print("The core .fit() method failed. This confirms a deep incompatibility.")
        print(f"Error details: {e}")
        return # Exit the function

    # --- 6. Evaluate the Model ---
    print("\n--- Model Evaluation ---")
    predictions = model.predict(X_test)
    report = classification_report(y_test, predictions)
    print(report)

    # --- 7. Save the Model ---
    if not os.path.exists(MODEL_DIR):
        os.makedirs(MODEL_DIR)
    model_path = os.path.join(MODEL_DIR, "bitcoin_trader_model_xgb_debug.pkl")
    joblib.dump(model, model_path)
    print(f"\nModel saved successfully to {model_path}")

if __name__ == '__main__':
    train_model()