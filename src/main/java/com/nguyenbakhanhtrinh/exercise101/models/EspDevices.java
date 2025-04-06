package com.nguyenbakhanhtrinh.exercise101.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "esp_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EspDevices {

    @Id
    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;     // ID duy nhất của thiết bị (ví dụ: từ MAC)

    @Column(name = "name")
    private String name;         // Tên hiển thị của thiết bị (có thể đổi)

    @Column(name = "command_topic")
    private String commandTopic; // Topic để gửi lệnh (ví dụ: /devices/esp_device_XXXXXX/command)

    @Column(name = "is_light_on")
    private boolean isLightOn;   // Trạng thái đèn

    @Column(name = "is_rgb_mode")
    private boolean isRGBMode;   // Chế độ đèn RGB
}
