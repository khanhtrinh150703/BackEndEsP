package com.nguyenbakhanhtrinh.exercise101.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.eclipse.paho.client.mqttv3.MqttClient;

@Configuration
public class MqttConfig {
    private final String mqttBroker = "tcp://localhost:1883";
    private final String username = "IoTClient";
    private final String password = "IoTPass";
    private final String clientId = "mqttSpringBootClient";

    @Bean
    public MqttClient mqttClient() throws Exception {
        MqttClient client = new MqttClient(mqttBroker, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        options.setCleanSession(true);
        
        client.connect(options);
        return client;
    }
}
