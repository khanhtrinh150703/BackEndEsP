package com.nguyenbakhanhtrinh.exercise101.repositories;

import com.nguyenbakhanhtrinh.exercise101.models.MqttMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface IMqttMessageRepository extends JpaRepository<MqttMessageEntity, Long> {
}
