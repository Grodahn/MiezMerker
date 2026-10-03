package org.miezmerker.backend.repo;

import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
}
