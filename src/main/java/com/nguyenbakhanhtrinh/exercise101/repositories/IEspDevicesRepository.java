package com.nguyenbakhanhtrinh.exercise101.repositories;

import com.nguyenbakhanhtrinh.exercise101.models.EspDevices;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IEspDevicesRepository extends JpaRepository<EspDevices, String> {
    EspDevices findByDeviceId(String deviceId);
}
