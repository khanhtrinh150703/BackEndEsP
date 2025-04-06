package com.nguyenbakhanhtrinh.exercise101.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.nguyenbakhanhtrinh.exercise101.services.MqttService;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MqttService mqttService;

    public WebSocketConfig(MqttService mqttService) {
        this.mqttService = mqttService;
        System.out.println("WebSocketConfig initialized");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(mqttService, "/mqtt").setAllowedOrigins("*");
        System.out.println("WebSocket handler registered at /mqtt");
    }
}