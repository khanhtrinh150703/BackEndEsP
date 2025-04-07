package com.nguyenbakhanhtrinh.exercise101.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nguyenbakhanhtrinh.exercise101.models.EspDevices;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@EnableScheduling
public class MqttService extends TextWebSocketHandler {

    private MqttClient mqttClient;
    private final EspDevicesServices espDevicesServices;
    private static final String DEFAULT_TOPIC_1 = "/devices/notification";
    private static final String DEFAULT_TOPIC_2 = "/speech/command";
    private final int qos = 1;

    // Danh sách các session WebSocket
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    // ObjectMapper để serialize JSON
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttService(EspDevicesServices espDevicesServices) {
        this.espDevicesServices = espDevicesServices;
        initializeMqttClient();
    }

    private void initializeMqttClient() {
        int retryCount = 0;
        int maxRetries = 5;
        long retryDelay = 5000; // 5 giây

        while (retryCount < maxRetries) {
            try {
                mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);

                System.out.println("Attempting to connect to MQTT broker at tcp://localhost:1883...");
                mqttClient.connect(options);
                System.out.println("Connected to MQTT broker successfully");

                mqttClient.setCallback(new MqttCallback() {
                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        System.out.println("Received MQTT message: " + new String(message.getPayload()) + " on topic: " + topic);
                        handleIncomingMessage(topic, message);
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        System.out.println("MQTT connection lost: " + cause.getMessage());
                        initializeMqttClient(); // Tái khởi tạo khi mất kết nối
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        System.out.println("Message delivery complete.");
                    }
                });

                List<EspDevices> allDevices = espDevicesServices.getAllDevices();
                List<String> topicsList = new ArrayList<>();
                List<Integer> qosList = new ArrayList<>();

                topicsList.add(DEFAULT_TOPIC_1);
                topicsList.add(DEFAULT_TOPIC_2);
                qosList.add(qos);
                qosList.add(qos);

                for (EspDevices device : allDevices) {
                    String commandTopic = device.getCommandTopic();
                    if (commandTopic != null && !commandTopic.isEmpty()) {
                        topicsList.add(commandTopic);
                        qosList.add(qos);
                        System.out.println("Adding command topic for device " + device.getDeviceId() + ": " + commandTopic);
                    }
                }

                String[] topics = topicsList.toArray(new String[0]);
                int[] qosLevels = qosList.stream().mapToInt(Integer::intValue).toArray();

                mqttClient.subscribe(topics, qosLevels);
                System.out.println("Subscribed to topics: " + String.join(", ", topicsList));
                break;
            } catch (MqttException e) {
                retryCount++;
                System.out.println("Error connecting to MQTT broker (Attempt " + retryCount + "/" + maxRetries + "): " + e.getMessage());
                if (retryCount >= maxRetries) {
                    System.out.println("Max retries reached. MQTT client failed to initialize.");
                    break;
                }
                try {
                    Thread.sleep(retryDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }


    private void subscribeToDeviceTopic(String topic) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.out.println("MQTT Client is not connected. Cannot subscribe to topic: " + topic);
            initializeMqttClient();
            if (mqttClient == null || !mqttClient.isConnected()) {
                System.out.println("Failed to reconnect MQTT Client. Subscription skipped for topic: " + topic);
                return;
            }
        }
    
        try {
            mqttClient.subscribe(topic, qos);
            System.out.println("✅ Subscribed to new topic: " + topic);
        } catch (MqttException e) {
            System.out.println("❌ Error subscribing to topic " + topic + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleIncomingMessage(String topic, MqttMessage message) {
        String receivedMsg = new String(message.getPayload());
        System.out.println("Received message: " + receivedMsg);
        System.out.println("Topic: " + topic);
        EspDevices updatedDevice = handleMqttMessage(topic, receivedMsg);
        if (updatedDevice != null) {
            sendMessageToClients(updatedDevice);
        }
    }

    public EspDevices handleMqttMessage(String topic, String message) {
        if (topic == null || message == null) {
            System.out.println("⚠️ Null topic or message received");
            return null;
        }
    
        String deviceId = extractDeviceIdFromTopic(topic);
        EspDevices device = (deviceId != null) ? espDevicesServices.getDeviceById(deviceId) : null;
    
        // Handle special topics first
        if (DEFAULT_TOPIC_1.equals(topic)) {
            return handleNewDeviceRegistration(message);
        } else if (DEFAULT_TOPIC_2.equals(topic)) {
            handleGlobalStateChange(message);
            return null;
        }
    
        // Handle device-specific commands
        if (deviceId == null) {
            System.out.println("❓ Invalid topic format: " + topic);
            return null;
        }
    
        if (device == null && !"deleteNVS".equals(message)) {
            System.out.println("⚠️ Device not found for ID: " + deviceId);
            return null;
        }
    
        try {
            if ("deleteNVS".equals(message)) {
                if (device != null) {
                    espDevicesServices.deleteDevice(deviceId);
                    System.out.println("✅ Deleted device with ID: " + deviceId);
                } else {
                    System.out.println("⚠️ Device already deleted or not found for ID: " + deviceId);
                }
                return null;
            } else if (message.startsWith("name/")) {
                return handleNameChange(device, message, deviceId);
            } else {
                return handleDeviceStateChange(device, message, deviceId);
            }
        } catch (Exception e) {
            System.err.println("❌ Error processing message '" + message + "' for topic " + topic + ": " + e.getMessage());
            return null;
        }
    }
    
    private String extractDeviceIdFromTopic(String topic) {
        String[] parts = topic.split("/");
        return (parts.length >= 3) ? parts[2] : null;
    }
    
    private EspDevices handleNewDeviceRegistration(String message) {
        String[] msgParts = message.split("/");
        if (msgParts.length < 3) {
            System.out.println("⚠️ Invalid message format: " + message);
            return null;
        }
    
        String deviceId = msgParts[2];
        System.out.println("📥 New device notification received. Device ID: " + deviceId);
    
        EspDevices existingDevice = espDevicesServices.getDeviceById(deviceId);
        if (existingDevice != null) {
            System.out.println("✅ Device already exists: " + existingDevice.getDeviceId());
            return null;
        }
    
        EspDevices newDevice = new EspDevices();
        newDevice.setDeviceId(deviceId);
        newDevice.setName("ESP_" + deviceId);
        newDevice.setLightOn(false);
        newDevice.setRGBMode(false);
        newDevice.setCommandTopic("/devices/" + deviceId + "/command");
    
        EspDevices savedDevice = espDevicesServices.addDevice(newDevice);
        System.out.println("✅ Device added: " + savedDevice.getDeviceId() + " - " + savedDevice.getName());
        subscribeToDeviceTopic(savedDevice.getCommandTopic());
        return savedDevice;
    }
    
    private void handleGlobalStateChange(String message) {
        if ("turn on".equals(message)) {
            // espDevicesServices.updateStateLight(true);
            System.out.println("🔆 Global turn on command received");
        } else if ("turn off".equals(message)) {
            // espDevicesServices.updateStateLight(false);
            System.out.println("🌙 Global turn off command received");
        } else {
            System.out.println("❓ Unknown global command: " + message);
        }
    }
    
    private EspDevices handleNameChange(EspDevices device, String message, String deviceId) {
        String[] parts = message.split("/", 2);
        if (parts.length == 2 && !parts[1].isEmpty()) {
            device.setName(parts[1]);
            espDevicesServices.updateDevice(device);
            System.out.println("✅ Updated device name to: " + device.getName() + " for ID: " + deviceId);
            return device;
        } else {
            System.out.println("⚠️ Invalid name format: " + message);
            return null;
        }
    }
    
    private EspDevices handleDeviceStateChange(EspDevices device, String message, String deviceId) {
        switch (message) {
            case "on":
                device.setLightOn(true);
                break;
            case "off":
                device.setLightOn(false);
                break;
            case "onRGB":
                device.setRGBMode(true);
                break;
            case "offRGB":
                device.setRGBMode(false);
                break;
            default:
                System.out.println("❓ Unknown command: " + message + " for device: " + deviceId);
                return null;
        }
        espDevicesServices.updateDevice(device);
        System.out.println("✅ Updated device " + deviceId + ": LightOn=" + device.isLightOn() + ", RGBMode=" + device.isRGBMode());
        return device;
    }

    public EspDevices handleVoiceCommandPublic(String command) {
        EspDevices updatedDevice = handleVoiceCommand(command);
        if (updatedDevice != null) {
            sendMessageToClients(updatedDevice);
        }
        return updatedDevice;
    }

    private EspDevices handleVoiceCommand(String command) {
        String deviceId = "esp1";
        EspDevices device = espDevicesServices.getDeviceById(deviceId);
        if (device == null) {
            System.out.println("Device not found: " + deviceId);
            return null;
        }

        switch (command) {
            case "turn on":
                espDevicesServices.turnOnLight(deviceId);
                try {
                    publishMessage("on", "/devices/" + deviceId + "/command");
                    device.setLightOn(true);
                } catch (MqttException e) {
                    System.out.println("Failed to publish command: " + e.getMessage());
                    return null;
                }
                break;
            case "turn off":
                espDevicesServices.turnOffLight(deviceId);
                try {
                    publishMessage("off", "/devices/" + deviceId + "/command");
                    device.setLightOn(false);
                } catch (MqttException e) {
                    System.out.println("Failed to publish command: " + e.getMessage());
                    return null;
                }
                break;
            case "rgb mode":
                espDevicesServices.setRgbMode(deviceId, true);
                try {
                    publishMessage("onRGB", "/devices/" + deviceId + "/command");
                    device.setRGBMode(true);
                } catch (MqttException e) {
                    System.out.println("Failed to publish command: " + e.getMessage());
                    return null;
                }
                break;
            case "end rgb mode":
                espDevicesServices.setRgbMode(deviceId, false);
                try {
                    publishMessage("offRGB", "/devices/" + deviceId + "/command");
                    device.setRGBMode(false);
                } catch (MqttException e) {
                    System.out.println("Failed to publish command: " + e.getMessage());
                    return null;
                }
                break;
            default:
                System.out.println("Unknown voice command: " + command);
                return null;
        }
        espDevicesServices.updateDevice(device);
        return device;
    }

    public void publishMessage(String message, String topicString) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.out.println("MQTT Client is not connected. Reconnecting...");
            initializeMqttClient();
        }
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(qos);
        mqttClient.publish(topicString, mqttMessage);
    }

    // WebSocket methods (Spring WebSocket)
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("New WebSocket connection established: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        System.out.println("WebSocket connection closed: " + session.getId() + " - Status: " + status);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        System.out.println("Received message from client " + session.getId() + ": " + message.getPayload());
        handleVoiceCommand(message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        System.out.println("WebSocket error for session " + session.getId() + ": " + exception.getMessage());
        sessions.remove(session);
    }

    private void sendMessageToClients(EspDevices device) {
        if (device == null) {
            return;
        }

        String jsonMessage;
        try {
            jsonMessage = objectMapper.writeValueAsString(device);
        } catch (Exception e) {
            System.out.println("Error serializing EspDevices to JSON: " + e.getMessage());
            return;
        }

        sessions.removeIf(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(jsonMessage));
                    System.out.println("🔴 Sending EspDevices to WebSocket client: " + device.getDeviceId());
                    return false;
                }
                return true;
            } catch (IOException e) {
                System.out.println("Client disconnected, removing session " + session.getId() + ": " + e.getMessage());
                return true;
            }
        });
    }

    @Scheduled(fixedRate = 60000)
    public void cleanSessions() {
        sessions.removeIf(session -> {
            if (!session.isOpen()) {
                System.out.println("Removing closed session: " + session.getId());
                return true;
            }
            try {
                session.sendMessage(new TextMessage("{\"type\":\"heartbeat\"}"));
                return false;
            } catch (IOException e) {
                System.out.println("Error sending heartbeat, removing session " + session.getId() + ": " + e.getMessage());
                return true;
            }
        });
    }

    public boolean isMqttConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}