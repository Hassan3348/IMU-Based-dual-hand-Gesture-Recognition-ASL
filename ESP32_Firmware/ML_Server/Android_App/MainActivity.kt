import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:http/http.dart' as http;
import 'package:permission_handler/permission_handler.dart';

// ── CONFIG ─────────────────────────────────────────────────
const String LEFT_GLOVE_NAME     = "GLOVE_LEFT";
const String RIGHT_GLOVE_NAME    = "GLOVE_RIGHT";
const String CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8";
const String SERVER_IP           = "10.185.104.26";
const int    SERVER_PORT         = 5000;
const int    RECORD_SECONDS      = 4;

void main() {
  runApp(const SignSpeakkApp());
}

class SignSpeakkApp extends StatelessWidget {
  const SignSpeakkApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SignSpeakk',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1D9E75)),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {

  // ── STATE ─────────────────────────────────────────────────
  bool leftConnected   = false;
  bool rightConnected  = false;
  bool isRecording     = false;
  bool isProcessing    = false;
  String gesture       = "—";
  double confidence    = 0.0;
  List<String> history = [];
  int countdown        = 0;

  BluetoothDevice? leftDevice;
  BluetoothDevice? rightDevice;

  List<List<dynamic>> leftPackets  = [];
  List<List<dynamic>> rightPackets = [];
  double recordStart = 0;

  // ── INIT ──────────────────────────────────────────────────
  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  Future<void> _requestPermissions() async {
    await [
      Permission.bluetooth,
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse,
    ].request();
  }

  // ── SCAN & CONNECT ────────────────────────────────────────
  Future<void> _connectGloves() async {
    setState(() { leftConnected = false; rightConnected = false; });
    _showSnack("Scanning for gloves...");

    await FlutterBluePlus.startScan(timeout: const Duration(seconds: 10));

    FlutterBluePlus.scanResults.listen((results) async {
      for (ScanResult r in results) {
        if (r.device.platformName == LEFT_GLOVE_NAME && !leftConnected) {
          leftDevice = r.device;
          await leftDevice!.connect();
          await _subscribeToGlove(leftDevice!, isLeft: true);
          setState(() => leftConnected = true);
          _showSnack("Left glove connected!");
        }
        if (r.device.platformName == RIGHT_GLOVE_NAME && !rightConnected) {
          rightDevice = r.device;
          await rightDevice!.connect();
          await _subscribeToGlove(rightDevice!, isLeft: false);
          setState(() => rightConnected = true);
          _showSnack("Right glove connected!");
        }
      }
    });
  }

  Future<void> _subscribeToGlove(BluetoothDevice device,
      {required bool isLeft}) async {
    List<BluetoothService> services = await device.discoverServices();
    for (BluetoothService service in services) {
      for (BluetoothCharacteristic c in service.characteristics) {
        if (c.characteristicUuid.toString() == CHARACTERISTIC_UUID) {
          await c.setNotifyValue(true);
          c.lastValueStream.listen((value) {
            if (!isRecording) return;
            try {
              String raw         = utf8.decode(value).trim();
              List<String> parts = raw.split(',');
              if (parts.length != 30) return;
              List<dynamic> row  = [
                DateTime.now().millisecondsSinceEpoch / 1000.0 - recordStart,
                ...parts.map((v) => double.parse(v))
              ];
              if (isLeft) leftPackets.add(row);
              else rightPackets.add(row);
            } catch (_) {}
          });
        }
      }
    }
  }

  // ── RECORD ────────────────────────────────────────────────
  Future<void> _startRecording() async {
    if (!leftConnected || !rightConnected) {
      _showSnack("Connect both gloves first!");
      return;
    }

    leftPackets  = [];
    rightPackets = [];

    for (int i = 3; i > 0; i--) {
      setState(() => countdown = i);
      await Future.delayed(const Duration(seconds: 1));
    }

    setState(() { countdown = 0; isRecording = true; });
    recordStart = DateTime.now().millisecondsSinceEpoch / 1000.0;

    for (int i = RECORD_SECONDS; i > 0; i--) {
      setState(() => countdown = i);
      await Future.delayed(const Duration(seconds: 1));
    }

    setState(() { isRecording = false; isProcessing = true; countdown = 0; });

    debugPrint("Recording done.");
    debugPrint("Left packets: ${leftPackets.length}");
    debugPrint("Right packets: ${rightPackets.length}");

    await _sendToServer();
  }

  // ── SEND TO SERVER ────────────────────────────────────────
  Future<void> _sendToServer() async {
    try {
      final url  = Uri.parse("http://$SERVER_IP:$SERVER_PORT/predict");
      final body = jsonEncode({
        "left":  leftPackets,
        "right": rightPackets,
      });

      debugPrint("Sending to server: ${url.toString()}");
      debugPrint("Payload size: ${body.length} bytes");

      final response = await http.post(
        url,
        headers: {"Content-Type": "application/json"},
        body: body,
      ).timeout(const Duration(seconds: 15));

      debugPrint("Response status: ${response.statusCode}");
      debugPrint("Response body: ${response.body}");

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        setState(() {
          gesture    = data["gesture"].toString().toUpperCase();
          confidence = (data["confidence"] as num).toDouble();
          if (gesture != "UNKNOWN") history.insert(0, gesture);
          if (history.length > 5) history = history.sublist(0, 5);
        });
      } else {
        _showSnack("Server error: ${response.statusCode}");
      }
    } catch (e) {
      debugPrint("ERROR: $e");
      _showSnack("Error: $e");
    }
    setState(() => isProcessing = false);
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg)));
  }

  // ── UI ────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0F0F1A),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            children: [
              // Header
              const Text("SIGNSPEAKK",
                style: TextStyle(color: Color(0xFF1D9E75), fontSize: 13,
                    letterSpacing: 3, fontWeight: FontWeight.w500)),
              const SizedBox(height: 4),
              const Text("Sign Recognizer",
                style: TextStyle(color: Colors.white, fontSize: 22,
                    fontWeight: FontWeight.w600)),
              const SizedBox(height: 20),

              // Glove badges
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _statusBadge("LEFT",  leftConnected),
                  const SizedBox(width: 12),
                  _statusBadge("RIGHT", rightConnected),
                ],
              ),
              const SizedBox(height: 24),

              // Gesture circle
              Container(
                width: 160, height: 160,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: const Color(0xFF1D9E75), width: 2),
                  color: const Color(0xFF1A1A2E),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (countdown > 0)
                      Text("$countdown",
                        style: const TextStyle(color: Colors.white,
                            fontSize: 48, fontWeight: FontWeight.bold))
                    else if (isProcessing)
                      const CircularProgressIndicator(
                          color: Color(0xFF1D9E75))
                    else
                      Text(gesture,
                        style: const TextStyle(color: Colors.white,
                            fontSize: 28, fontWeight: FontWeight.bold)),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                isRecording  ? "Recording..."  :
                isProcessing ? "Processing..." : "Last detected gesture",
                style: const TextStyle(color: Colors.grey, fontSize: 12),
              ),
              const SizedBox(height: 28),

              // Connect button
              SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: _connectGloves,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: const Color(0xFF1D9E75),
                    side: const BorderSide(color: Color(0xFF1D9E75)),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12)),
                  ),
                  child: const Text("Connect Gloves"),
                ),
              ),
              const SizedBox(height: 12),

              // Record button
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: (isRecording || isProcessing)
                      ? null
                      : _startRecording,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF1D9E75),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12)),
                  ),
                  child: Text(
                    isRecording  ? "Recording..."  :
                    isProcessing ? "Processing..." : "Start Recording",
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w600),
                  ),
                ),
              ),
              const SizedBox(height: 24),

              // History
              if (history.isNotEmpty) ...[
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text("Recent gestures",
                    style: TextStyle(color: Colors.grey, fontSize: 12)),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8, runSpacing: 8,
                  children: history.map((g) => Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 14, vertical: 6),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1A1A2E),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(color: Colors.white12),
                    ),
                    child: Text(g,
                      style: const TextStyle(
                          color: Colors.white70, fontSize: 13)),
                  )).toList(),
                ),
              ]
            ],
          ),
        ),
      ),
    );
  }

  Widget _statusBadge(String label, bool connected) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      decoration: BoxDecoration(
        color: connected
            ? const Color(0xFF0F3D2E)
            : const Color(0xFF2A1A1A),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: connected
              ? const Color(0xFF1D9E75)
              : Colors.red.shade900),
      ),
      child: Text(label,
        style: TextStyle(
          color: connected
              ? const Color(0xFF1D9E75)
              : Colors.red.shade400,
          fontSize: 13, fontWeight: FontWeight.w500)),
    );
  }
}
