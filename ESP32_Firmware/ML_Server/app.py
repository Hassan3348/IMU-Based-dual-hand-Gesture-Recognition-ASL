import numpy as np
import joblib
from scipy.interpolate import interp1d
from flask import Flask, request, jsonify

app = Flask(__name__)

# ── CONFIG ─────────────────────────────────────────────────
TARGET_HZ            = 50
CONFIDENCE_THRESHOLD = 40.0
GESTURE_NAMES = {
    0:"agree", 1:"getup", 2:"hello", 3:"helpyou", 4:"ilu",
    5:"name",  6:"slow",  7:"small", 8:"time",    9:"yes"
}

# ── LOAD MODEL ─────────────────────────────────────────────
print("Loading model...")
clf         = joblib.load("gesture_model.pkl")
scaler      = joblib.load("scaler.pkl")
stable_mask = joblib.load("feature_mask.pkl")
print("Model ready!")

# ── RESAMPLE ───────────────────────────────────────────────
def resample_hand(rows):
    if len(rows) < 2:
        return None
    timestamps  = np.array([r[0] for r in rows])
    sensor_data = np.array([r[1:] for r in rows])
    t_start, t_end = timestamps[0], timestamps[-1]
    duration = t_end - t_start
    if duration <= 0:
        return None
    n_samples = int(duration * TARGET_HZ)
    if n_samples < 2:
        return None
    t_uniform = np.linspace(t_start, t_end, n_samples)
    resampled = np.zeros((n_samples, sensor_data.shape[1]))
    for ch in range(sensor_data.shape[1]):
        _, unique_idx = np.unique(timestamps, return_index=True)
        t_clean  = timestamps[unique_idx]
        ch_clean = sensor_data[unique_idx, ch]
        if len(t_clean) < 2:
            resampled[:, ch] = ch_clean[0]
            continue
        f = interp1d(t_clean, ch_clean, kind='linear',
                     bounds_error=False,
                     fill_value=(ch_clean[0], ch_clean[-1]))
        resampled[:, ch] = f(t_uniform)
    return resampled

# ── FEATURE EXTRACTION ─────────────────────────────────────
def extract_features(arr):
    features = []
    for col in range(arr.shape[1]):
        ch = arr[:, col].astype(float)
        features.append(np.mean(ch))
        features.append(np.std(ch))
        features.append(np.sqrt(np.mean(ch**2)))
        features.append(np.max(ch) - np.min(ch))
        features.append(np.sum(np.abs(np.diff(ch))))
        features.append(np.percentile(ch, 25))
        features.append(np.percentile(ch, 75))
        features.append(np.sum(np.abs(np.fft.rfft(ch))**2))
    return features

# ── PREDICT ENDPOINT ───────────────────────────────────────
@app.route("/predict", methods=["POST"])
def predict():
    print("Request received!")
    data       = request.get_json()
    left_rows  = data.get("left",  [])
    right_rows = data.get("right", [])

    print(f"Left packets: {len(left_rows)} | Right packets: {len(right_rows)}")

    if len(left_rows) < 10 or len(right_rows) < 10:
        return jsonify({"error": "Too few packets"}), 400

    left_resampled  = resample_hand(left_rows)
    right_resampled = resample_hand(right_rows)

    if left_resampled is None or right_resampled is None:
        return jsonify({"error": "Resampling failed"}), 400

    left_feats  = extract_features(left_resampled)
    right_feats = extract_features(right_resampled)
    features    = np.array(left_feats + right_feats).reshape(1, -1)

    features_stable = features[:, stable_mask]
    features_scaled = scaler.transform(features_stable)

    prediction = clf.predict(features_scaled)[0]
    confidence = round(float(np.max(clf.predict_proba(features_scaled)) * 100), 1)

    print(f"Prediction: {GESTURE_NAMES[prediction]} | Confidence: {confidence}%")

    if confidence < CONFIDENCE_THRESHOLD:
        return jsonify({"gesture": "unknown", "confidence": confidence})

    return jsonify({
        "gesture":    GESTURE_NAMES[prediction],
        "confidence": confidence
    })

# ── HEALTH CHECK ───────────────────────────────────────────
@app.route("/ping", methods=["GET"])
def ping():
    return jsonify({"status": "ok"})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
