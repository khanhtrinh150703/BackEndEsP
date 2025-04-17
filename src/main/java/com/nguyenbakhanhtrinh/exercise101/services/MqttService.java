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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@EnableScheduling
public class MqttService extends TextWebSocketHandler {

    private MqttClient mqttClient;
    private final EspDevicesServices espDevicesServices;
    private static final String NOTIFICATION_TOPIC = "/devices/notification";
    private static final String SPEECH_TOPIC = "/speech/command";
    private static final int QOS = 1;
    private static final int MAX_SESSIONS = 100;

    // Thread-safe list of WebSocket sessions
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    // Track last activity time for sessions
    private final Map<WebSocketSession, Long> sessionLastActive = new ConcurrentHashMap<>();
    // ObjectMapper for JSON serialization
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Track processed delete messages to prevent loops
    private final Set<String> processedDeleteMessages = new HashSet<>();

    public MqttService(EspDevicesServices espDevicesServices) {
        this.espDevicesServices = espDevicesServices;
        initializeMqttClient();
    }

    private void initializeMqttClient() {
        int retryCount = 0;
        int maxRetries = 5;
        long retryDelay = 5000; // 5 seconds

        while (retryCount < maxRetries) {
            try {
                mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);

                System.out.println("Connecting to MQTT broker at tcp://localhost:1883...");
                mqttClient.connect(options);
                System.out.println("Connected to MQTT broker successfully");

                mqttClient.setCallback(new MqttCallback() {
                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload());
                        System.out.println("📩 Received MQTT message: '" + payload + "' on topic: " + topic + " at " + System.currentTimeMillis());
                        handleIncomingMessage(topic, message);
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        System.out.println("MQTT connection lost: " + cause.getMessage());
                        initializeMqttClient();
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        System.out.println("Message delivery complete.");
                    }
                });

                // Subscribe to default and device-specific topics
                List<EspDevices> allDevices = espDevicesServices.getAllDevices();
                List<String> topics = new ArrayList<>();
                List<Integer> qosLevels = new ArrayList<>();

                topics.add(NOTIFICATION_TOPIC);
                topics.add(SPEECH_TOPIC);
                qosLevels.add(QOS);
                qosLevels.add(QOS);

                for (EspDevices device : allDevices) {
                    String commandTopic = device.getCommandTopic();
                    if (commandTopic != null && !commandTopic.isEmpty()) {
                        topics.add(commandTopic);
                        qosLevels.add(QOS);
                        System.out.println("Subscribing to command topic for device " + device.getDeviceId() + ": " + commandTopic);
                    }
                }

                mqttClient.subscribe(topics.toArray(new String[0]), qosLevels.stream().mapToInt(Integer::intValue).toArray());
                System.out.println("Subscribed to topics: " + String.join(", ", topics));
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
            System.out.println("MQTT client is not connected. Reconnecting...");
            initializeMqttClient();
            if (mqttClient == null || !mqttClient.isConnected()) {
                System.out.println("Failed to reconnect MQTT client. Subscription skipped for topic: " + topic);
                return;
            }
        }

        try {
            mqttClient.subscribe(topic, QOS);
            System.out.println("✅ Subscribed to topic: " + topic);
        } catch (MqttException e) {
            System.out.println("❌ Error subscribing to topic " + topic + ": " + e.getMessage());
        }
    }

    private void handleIncomingMessage(String topic, MqttMessage message) {
        String payload = new String(message.getPayload());
        EspDevices updatedDevice = handleMqttMessage(topic, payload);
        if (updatedDevice != null) {
            sendMessageToClients(updatedDevice, "update");
        }
    }

    private EspDevices handleMqttMessage(String topic, String message) {
        if (topic == null || message == null) {
            System.out.println("⚠️ Null topic or message received");
            return null;
        }

        String deviceId = extractDeviceIdFromTopic(topic);
        EspDevices device = (deviceId != null) ? espDevicesServices.getDeviceById(deviceId) : null;

        try {
            if (NOTIFICATION_TOPIC.equals(topic)) {
                return handleNewDeviceRegistration(message);
            } else if (SPEECH_TOPIC.equals(topic)) {
                handleGlobalStateChange(message);
                return null;
            }

            if (deviceId == null) {
                System.out.println("❓ Invalid topic format: " + topic);
                return null;
            }

            if (device == null && !"deleteNVS".equals(message)) {
                System.out.println("⚠️ Device not found for ID: " + deviceId);
                return null;
            }

            if ("deleteNVS".equals(message)) {
                String deleteKey = deviceId + ":" + message;
                if (processedDeleteMessages.contains(deleteKey)) {
                    System.out.println("⚠️ Duplicate deleteNVS message for device: " + deviceId + ", skipping");
                    return null;
                }
                if (device != null) {
                    espDevicesServices.deleteDevice(deviceId);
                    sendMessageToClients(device, "delete");
                    processedDeleteMessages.add(deleteKey);
                    System.out.println("✅ Deleted device: " + deviceId);
                } else {
                    System.out.println("⚠️ Device already deleted or not found: " + deviceId);
                }
                return null;
            } else if (message.startsWith("name/")) {
                return handleNameChange(device, message, deviceId);
            } else {
                return handleDeviceStateChange(device, message, deviceId);
            }
        } catch (Exception e) {
            System.out.println("❌ Error processing message '" + message + "' for topic " + topic + ": " + e.getMessage());
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
        System.out.println("📥 New device notification received: " + deviceId);

        EspDevices existingDevice = espDevicesServices.getDeviceById(deviceId);
        if (existingDevice != null) {
            System.out.println("✅ Device already exists: " + deviceId);
            return null;
        }

        EspDevices newDevice = new EspDevices();
        newDevice.setDeviceId(deviceId);
        newDevice.setName("ESP_" + deviceId);
        newDevice.setLightOn(false);
        newDevice.setRGBMode(false);
        newDevice.setCommandTopic("/devices/" + deviceId + "/command");

        EspDevices savedDevice = espDevicesServices.addDevice(newDevice);
        System.out.println("✅ Added device: " + savedDevice.getDeviceId());
        subscribeToDeviceTopic(savedDevice.getCommandTopic());
        return savedDevice;
    }

    private void handleGlobalStateChange(String message) {
        List<EspDevices> allDevices = espDevicesServices.getAllDevices();
        if ("turn on".equals(message)) {
            System.out.println("🔆 Global turn on command received");
            espDevicesServices.updateStateLight(true);
            for (EspDevices device : allDevices) {
                device.setLightOn(true);
                espDevicesServices.updateDevice(device);
                sendMessageToClients(device, "update");
                try {
                    publishMessage("on", device.getCommandTopic());
                    System.out.println("✅ Sent 'on' to " + device.getDeviceId());
                } catch (MqttException e) {
                    System.out.println("❌ Failed to publish 'on' to " + device.getDeviceId() + ": " + e.getMessage());
                }
            }
        } else if ("turn off".equals(message)) {
            System.out.println("🌙 Global turn off command received");
            espDevicesServices.updateStateLight(false);
            for (EspDevices device : allDevices) {
                device.setLightOn(false);
                espDevicesServices.updateDevice(device);
                sendMessageToClients(device, "update");
                try {
                    publishMessage("off", device.getCommandTopic());
                    System.out.println("✅ Sent 'off' to " + device.getDeviceId());
                } catch (MqttException e) {
                    System.out.println("❌ Failed to publish 'off' to " + device.getDeviceId() + ": " + e.getMessage());
                }
            }
        } else {
            System.out.println("❓ Unknown global command: " + message);
        }
    }

    private EspDevices handleNameChange(EspDevices device, String message, String deviceId) {
        String[] parts = message.split("/", 2);
        if (parts.length == 2 && !parts[1].isEmpty()) {
            device.setName(parts[1]);
            espDevicesServices.updateDevice(device);
            System.out.println("✅ Updated device name to: " + parts[1] + " for ID: " + deviceId);
            return device;
        }
        System.out.println("⚠️ Invalid name format: " + message);
        return null;
    }

    private EspDevices handleDeviceStateChange(EspDevices device, String message, String deviceId) {
        switch (message) {
            case "on":
                espDevicesServices.turnOnLight(deviceId);
                device.setLightOn(true);
                break;
            case "off":
                espDevicesServices.turnOffLight(deviceId);
                device.setLightOn(false);
                break;
            case "onRGB":
                espDevicesServices.setRgbMode(deviceId, true);
                device.setRGBMode(true);
                break;
            case "offRGB":
                espDevicesServices.setRgbMode(deviceId, false);
                device.setRGBMode(false);
                break;
            default:
                System.out.println("❓ Unknown command: " + message + " for device: " + deviceId);
                return null;
        }
        System.out.println("✅ Updated device " + deviceId + ": LightOn=" + device.isLightOn() + ", RGBMode=" + device.isRGBMode());
        return device;
    }

    public EspDevices handleVoiceCommandPublic(String command) {
        EspDevices updatedDevice = handleVoiceCommand(command);
        if (updatedDevice != null) {
            sendMessageToClients(updatedDevice, "update");
        }
        return updatedDevice;
    }

    private EspDevices handleVoiceCommand(String command) {
        String deviceId = "esp1"; // Hardcoded for simplicity; consider making dynamic
        EspDevices device = espDevicesServices.getDeviceById(deviceId);
        if (device == null) {
            System.out.println("⚠️ Device not found: " + deviceId);
            return null;
        }

        String mqttMessage;
        boolean updateDevice = false;

        switch (command) {
            case "turn on":
                mqttMessage = "on";
                device.setLightOn(true);
                updateDevice = true;
                break;
            case "turn off":
                mqttMessage = "off";
                device.setLightOn(false);
                updateDevice = true;
                break;
            case "rgb mode":
                mqttMessage = "onRGB";
                device.setRGBMode(true);
                updateDevice = true;
                break;
            case "end rgb mode":
                mqttMessage = "offRGB";
                device.setRGBMode(false);
                updateDevice = true;
                break;
            default:
                System.out.println("❓ Unknown voice command: " + command);
                return null;
        }

        if (updateDevice) {
            try {
                publishMessage(mqttMessage, "/devices/" + deviceId + "/command");
                System.out.println("✅ Published voice command: " + mqttMessage + " for device: " + deviceId);
            } catch (MqttException e) {
                System.out.println("❌ Failed to publish voice command: " + e.getMessage());
                return null;
            }
        }

        return device;
    }

    public void publishMessage(String message, String topic) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            System.out.println("MQTT client is not connected. Reconnecting...");
            initializeMqttClient();
        }
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(QOS);
        mqttClient.publish(topic, mqttMessage);
        System.out.println("📤 Published message: " + message + " to topic: " + topic);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (sessions.size() >= MAX_SESSIONS) {
            try {
                session.close(CloseStatus.SERVER_ERROR.withReason("Maximum sessions reached"));
                System.out.println("❌ Rejected connection: Maximum sessions reached");
            } catch (IOException e) {
                System.out.println("❌ Error closing session: " + e.getMessage());
            }
            return;
        }
        sessions.add(session);
        sessionLastActive.put(session, System.currentTimeMillis());
        System.out.println("✅ New WebSocket connection: " + session.getId() + ", Total sessions: " + sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        sessionLastActive.remove(session);
        System.out.println("❌ WebSocket connection closed: " + session.getId() + " - Status: " + status + ", Remaining sessions: " + sessions.size());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessionLastActive.put(session, System.currentTimeMillis()); // Update on activity
        System.out.println("📥 Received WebSocket message from " + session.getId() + ": " + message.getPayload());
        try {
            var jsonNode = objectMapper.readTree(message.getPayload());
            String type = jsonNode.get("type") != null ? jsonNode.get("type").asText() : null;

            if ("delete".equals(type)) {
                String deviceId = jsonNode.get("deviceId") != null ? jsonNode.get("deviceId").asText() : null;
                if (deviceId != null) {
                    EspDevices device = espDevicesServices.getDeviceById(deviceId);
                    if (device != null) {
                        espDevicesServices.deleteDevice(deviceId);
                        sendMessageToClients(device, "delete");
                        publishMessage("deleteNVS", device.getCommandTopic());
                        System.out.println("✅ Processed delete command for device: " + deviceId);
                    } else {
                        System.out.println("⚠️ Device not found for delete: " + deviceId);
                    }
                } else {
                    System.out.println("⚠️ Invalid delete command: deviceId missing");
                }
            } else {
                handleVoiceCommand(message.getPayload());
            }
        } catch (Exception e) {
            System.out.println("❌ Error processing WebSocket message: " + e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("❌ WebSocket error for session " + session.getId() + ": " + exception.getMessage());
        sessions.remove(session);
        sessionLastActive.remove(session);
    }

    private void sendMessageToClients(EspDevices device, String type) {
        if (device == null) {
            return;
        }

        System.out.println("📤 Sending " + type + " message for device: " + device.getDeviceId() + " to " + sessions.size() + " sessions");

        String jsonMessage;
        try {
            if ("delete".equals(type)) {
                jsonMessage = objectMapper.writeValueAsString(new DeleteMessage("delete", device.getDeviceId()));
            } else {
                jsonMessage = objectMapper.writeValueAsString(
                    new UpdateMessage("update", device.getDeviceId(), device.getName(), device.isLightOn(),
                        device.isRGBMode(), device.getCommandTopic())
                );
            }
        } catch (Exception e) {
            System.out.println("❌ Error serializing message to JSON: " + e.getMessage());
            return;
        }

        List<WebSocketSession> sessionsToRemove = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(jsonMessage));
                    System.out.println("🔴 Sent " + type + " message to session: " + session.getId());
                } else {
                    sessionsToRemove.add(session);
                }
            } catch (IOException e) {
                System.out.println("❌ Client disconnected, removing session " + session.getId() + ": " + e.getMessage());
                sessionsToRemove.add(session);
            }
        }

        sessions.removeAll(sessionsToRemove);
        sessionLastActive.keySet().removeAll(sessionsToRemove);
        if (!sessionsToRemove.isEmpty()) {
            System.out.println("🧹 Removed " + sessionsToRemove.size() + " closed sessions");
        }
    }

    // Helper classes for JSON serialization
    private static class DeleteMessage {
        public String type;
        public String deviceId;

        public DeleteMessage(String type, String deviceId) {
            this.type = type;
            this.deviceId = deviceId;
        }
    }

    private static class UpdateMessage {
        public String type;
        public String deviceId;
        public String name;
        public boolean lightOn;
        public boolean rgbmode;
        public String commandTopic;

        public UpdateMessage(String type, String deviceId, String name, boolean lightOn, boolean rgbmode, String commandTopic) {
            this.type = type;
            this.deviceId = deviceId;
            this.name = name;
            this.lightOn = lightOn;
            this.rgbmode = rgbmode;
            this.commandTopic = commandTopic;
        }
    }

    @Scheduled(fixedRate = 15000)
    public void cleanSessions() {
        long now = System.currentTimeMillis();
        long timeout = 300000; // 5 minutes
        List<WebSocketSession> sessionsToRemove = new ArrayList<>();
        for (WebSocketSession session : sessions) {
            if (!session.isOpen() || (now - sessionLastActive.getOrDefault(session, now)) > timeout) {
                sessionsToRemove.add(session);
                System.out.println("🧹 Removing session: " + session.getId() + " (closed or timed out)");
            } else {
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"heartbeat\"}"));
                } catch (IOException e) {
                    sessionsToRemove.add(session);
                    System.out.println("❌ Error sending heartbeat, removing session " + session.getId());
                }
            }
        }
        sessions.removeAll(sessionsToRemove);
        sessionLastActive.keySet().removeAll(sessionsToRemove);
        System.out.println("🧹 Removed " + sessionsToRemove.size() + " sessions, Remaining: " + sessions.size());
    }

    @Scheduled(fixedRate = 60000)
    public void logSessionCount() {
        System.out.println("📊 Current active sessions: " + sessions.size());
    }

    @Scheduled(fixedRate = 300000) // Clean every 5 minutes
    public void cleanProcessedMessages() {
        processedDeleteMessages.clear();
        System.out.println("🧹 Cleared processed delete messages");
    }

    @Scheduled(fixedRate = 60000)
    public void checkMqttConnection() {
        if (!isMqttConnected()) {
            System.out.println("⚠️ MQTT client is disconnected. Attempting to reconnect...");
            initializeMqttClient();
        }
    }

    public boolean isMqttConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}