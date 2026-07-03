#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- SETTINGS ---
#define MUX_ADDR     0x70
#define SENSOR_ADDR  0x68
#define PWR_MGMT_1   0x6B
#define ACCEL_XOUT_H 0x3B

// *** NAME FOR THE LEFT HAND ***
#define DEVICE_NAME  "GLOVE_LEFT" 

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Multiplexer Helper
void tcaSelect(uint8_t i) {
  if (i > 7) return;
  Wire.beginTransmission(MUX_ADDR);
  Wire.write(1 << i);
  Wire.endTransmission();
}

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println(">> LAPTOP CONNECTED! <<");
    };
    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> LAPTOP DISCONNECTED <<");
    }
};

void setup() {
  Serial.begin(115200);
  Serial.println("--- STARTING LEFT HAND SENSORS ---");

  // 1. Start Bluetooth
  BLEDevice::init(DEVICE_NAME);
  pServer = BLEDevice::createServer(); 
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ   |
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pCharacteristic->addDescriptor(new BLE2902());
  pService->start();
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);  
  BLEDevice::startAdvertising();
  Serial.println(">>> BLUETOOTH STARTED <<<");

  // 2. Wake Up Sensors (Pins 4 and 5 for ESP32-S3)
  Wire.begin(4, 5); 
  Wire.setClock(400000); 

  Serial.println("Initializing MPU6500s...");
  for (int i = 0; i < 5; i++) {
    tcaSelect(i);
    Wire.beginTransmission(SENSOR_ADDR);
    if (Wire.endTransmission() == 0) {
       Serial.print("Sensor "); Serial.print(i); Serial.println(" OK!");
       Wire.beginTransmission(SENSOR_ADDR);
       Wire.write(PWR_MGMT_1);
       Wire.write(0);
       Wire.endTransmission();
    } else {
       Serial.print("Sensor "); Serial.print(i); Serial.println(" MISSING (Will send 0s)");
    }
    delay(10);
  }
}

void loop() {
  if (deviceConnected) {
    String packet = "";
    
    // Read 5 Sensors
    for (int i = 0; i < 5; i++) {
      tcaSelect(i);
      Wire.beginTransmission(SENSOR_ADDR);
      Wire.write(ACCEL_XOUT_H); 
      Wire.endTransmission(false);
      
      Wire.requestFrom(SENSOR_ADDR, 14, true); 

      if (Wire.available() >= 14) {
        int16_t aX = (Wire.read() << 8) | Wire.read();
        int16_t aY = (Wire.read() << 8) | Wire.read();
        int16_t aZ = (Wire.read() << 8) | Wire.read();
        int16_t temp = (Wire.read() << 8) | Wire.read(); 
        int16_t gX = (Wire.read() << 8) | Wire.read();
        int16_t gY = (Wire.read() << 8) | Wire.read();
        int16_t gZ = (Wire.read() << 8) | Wire.read();

        packet += String(aX) + "," + String(aY) + "," + String(aZ) + "," + 
                  String(gX) + "," + String(gY) + "," + String(gZ);
      } else {
        packet += "0,0,0,0,0,0"; 
      }
      
      if (i < 4) packet += ","; 
    }

    pCharacteristic->setValue((char*)packet.c_str());
    pCharacteristic->notify();
    
    delay(40); 
  }

  if (!deviceConnected && oldDeviceConnected) {
      delay(500); 
      pServer->startAdvertising(); 
      Serial.println("Restarting advertising...");
      oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
      oldDeviceConnected = deviceConnected;
  }
}
