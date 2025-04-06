package com.nguyenbakhanhtrinh.exercise101.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MessageDto {
    private String topic;
    private String message;
    private LocalDateTime timestamp;
    private String device;
    private String source;

    public MessageDto(String topic, String message) {
        this.topic = topic;
        this.message = message;
    }
}