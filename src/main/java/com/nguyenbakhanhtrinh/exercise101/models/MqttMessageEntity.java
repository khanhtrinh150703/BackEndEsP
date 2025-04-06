package com.nguyenbakhanhtrinh.exercise101.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mqtt_messages")
@Getter
@Setter
public class MqttMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String topic;
    
    @Column(columnDefinition = "TEXT")
    private String message;
    
    private LocalDateTime timestamp;

    private String device;

    private String source;

    public MqttMessageEntity() {
        this.timestamp = LocalDateTime.now();
    }

    public MqttMessageEntity(String topic, String message) {
        this.topic = topic;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

}
