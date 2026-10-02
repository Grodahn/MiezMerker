package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.AppDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface AppDeviceRepository extends JpaRepository<AppDevice, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AppDevice d where d.id = :id")
    Optional<AppDevice> findLockedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AppDevice d where d.fingerprint = :fingerprint")
    Optional<AppDevice> findLockedByFingerprint(String fingerprint);
    Optional<AppDevice> findByFingerprint(String fingerprint);

    List<AppDevice> findByUserId(UUID userId);
}
