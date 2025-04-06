package com.nguyenbakhanhtrinh.exercise101.services;

import com.nguyenbakhanhtrinh.exercise101.models.EspDevices;
import com.nguyenbakhanhtrinh.exercise101.repositories.IEspDevicesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class EspDevicesServices {

    @Autowired
    private IEspDevicesRepository repository;

    // 1. Lấy danh sách tất cả thiết bị
    public List<EspDevices> getAllDevices() {
        return repository.findAll();
    }

    // 2. Lấy thiết bị theo ID
    public EspDevices getDeviceById(String deviceId) {
        return repository.findByDeviceId(deviceId);
    }

    // 3. Thêm mới thiết bị (nếu chưa tồn tại)
    public EspDevices addDevice(EspDevices device) {
        return repository.save(device);
    }

    // 4. Cập nhật thông tin thiết bị
    public EspDevices updateDevice(EspDevices device) {
        return repository.save(device);
    }

    // 5. Xóa thiết bị
    public void deleteDevice(String deviceId) {
        repository.deleteById(deviceId);
    }

    // 6. Bật đèn cho thiết bị
    public void turnOnLight(String deviceId) {
        repository.findById(deviceId).ifPresent(device -> {
            device.setLightOn(true);
            repository.save(device);
        });
    }

    // 7. Tắt đèn cho thiết bị
    public void turnOffLight(String deviceId) {
        repository.findById(deviceId).ifPresent(device -> {
            device.setLightOn(false);
            repository.save(device);
        });
    }

    // 8. Cập nhật trạng thái chế độ RGB
    public void setRgbMode(String deviceId, boolean enabled) {
        repository.findById(deviceId).ifPresent(device -> {
            device.setRGBMode(enabled);
            repository.save(device);
        });
    }
}
