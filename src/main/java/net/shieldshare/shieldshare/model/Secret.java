package net.shieldshare.shieldshare.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "secrets")
public class Secret {

    public enum State {
        ACTIVE,
        CONSUMED
    }

    @Id
    @Column(name = "secret_id", length = 24)
    String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    State state;

    @Column(name = "payload", nullable = false)
    byte[] payload;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    LocalDateTime consumedAt;
}
