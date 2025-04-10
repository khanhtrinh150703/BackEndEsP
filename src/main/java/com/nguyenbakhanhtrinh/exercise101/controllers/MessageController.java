package com.nguyenbakhanhtrinh.exercise101.controllers;

import com.nguyenbakhanhtrinh.exercise101.models.EspDevices;
import com.nguyenbakhanhtrinh.exercise101.services.EspDevicesServices;
import com.nguyenbakhanhtrinh.exercise101.services.MqttService;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:3001", "http://localhost:3003"})
@RestController
@RequestMapping("/api/mqtt")
public class MessageController {

    private final MqttService mqttService;
    private final EspDevicesServices espDevicesServices;

    public MessageController(MqttService mqttService, EspDevicesServices espDevicesServices) {
        this.mqttService = mqttService;
        this.espDevicesServices = espDevicesServices;
    }

    @GetMapping("/espDevices")
    public ResponseEntity<List<EspDevices>> getAllDevices() {
        List<EspDevices> devices = espDevicesServices.getAllDevices();
        return ResponseEntity.ok(devices);
    }

    @GetMapping("/status")
    public ResponseEntity<Boolean> checkMqttStatus() {
        boolean isConnected = mqttService.isMqttConnected();
        return ResponseEntity.ok(isConnected);
    }

    @PostMapping("/publish")
    public ResponseEntity<?> publishMessage(@RequestParam String message, @RequestParam String topic) {
        if (!mqttService.isMqttConnected()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("MQTT is not connected. Try again later.");
        }

        try {
            mqttService.publishMessage(message, topic);
            return ResponseEntity.ok("Message published: " + message + " to topic: " + topic);
        } catch (MqttException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to publish message: " + e.getMessage());
        }
    }

}