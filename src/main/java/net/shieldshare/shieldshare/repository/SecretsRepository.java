package net.shieldshare.shieldshare.repository;

import net.shieldshare.shieldshare.model.Secret;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SecretsRepository extends JpaRepository<Secret, String> {

    Optional<Secret> findById(@NonNull String id);
}
