# IMU-Based Dual-Hand Gesture Recognition System for ASL

A real-time American Sign Language (ASL) to Text conversion system using wearable dual-hand IMU gloves and Machine Learning.

## Overview
This system captures hand gestures using wearable gloves equipped with IMU sensors and classifies them into ASL letters in real time using a trained Random Forest model.

## System Architecture
```
Wearable Gloves (ESP32-S3 + MPU6500)
        ↓ BLE
Android App (BLE Receiver + HTTP Client)
        ↓ HTTP
Flask ML Server (Feature Extraction + Random Forest)
        ↓
Real-time Text Prediction
```

## Hardware
- 2× ESP32-S3 microcontrollers
- 5× MPU6500 IMU sensors per glove (10 total)
- TCA9548A I2C multiplexer

## ML Model
- **Algorithm:** Random Forest Classifier
- **Accuracy:** 92.6% (macro F1-score: 0.93)
- **Dataset:** 500 recordings, 10 ASL classes, 50 recordings/class
- **Features:** 480 extracted per window, reduced to ~128 via CoV filtering

## Repository Structure
```
ESP32_Firmware/     → Glove firmware (BLE + IMU data collection)
ML_Server/          → Flask inference server + trained model
Android_App/        → BLE receiver + HTTP client app
```

## Results
| Model         | Accuracy |
|---------------|----------|
| Random Forest | 92.6%    |
| KNN (k=15)    | 80.4%    |

## Authors
Hassan Zeb & Badar Hafeez Khalid
Namal University — Final Year Project 2026
Supervisor: Ms. Farkhanda Aziz
```
