package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.AppDevice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppDeviceRepository extends JpaRepository<AppDevice, UUID> {
    Optional<AppDevice> findByFingerprint(String fingerprint);

    List<AppDevice> findByUserId(UUID userId);
}
