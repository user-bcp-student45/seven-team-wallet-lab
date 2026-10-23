package com.seventeam.wallet.identity.application;

import com.seventeam.wallet.identity.adapter.out.UserRepository;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.shared.WalletException;
import com.seventeam.wallet.shared.WalletException.Kind;
import com.seventeam.wallet.shared.WalletProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final String INVALID_MESSAGE =
            "Correo o clave incorrectos. Revisa tus datos o inténtalo de nuevo más tarde.";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final WalletProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, WalletProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("kipu-dummy-password");
    }

    /** Mismo error para usuario inexistente, clave erronea y bloqueo, para no revelar cuentas. */
    public User authenticate(String email, String password) {
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            encoder.matches(password, dummyHash);
            throw invalid();
        }
        Instant now = clock.instant();
        if (user.lockedUntil() != null && now.isBefore(user.lockedUntil())) {
            throw invalid();
        }
        if (!encoder.matches(password, user.passwordHash())) {
            users.registerFailedLogin(user.id(), props.security().maxFailedLogins(),
                    now.plus(props.security().lockout()));
            throw invalid();
        }
        if (user.failedLogins() > 0 || user.lockedUntil() != null) {
            users.resetFailedLogins(user.id());
        }
        return user;
    }

    public User current(UUID userId) {
        return users.findById(userId).orElseThrow(
                () -> new WalletException(Kind.UNAUTHENTICATED, "UNAUTHENTICATED", "Inicia sesión para continuar."));
    }

    private static WalletException invalid() {
        return new WalletException(Kind.UNAUTHENTICATED, "INVALID_CREDENTIALS", INVALID_MESSAGE);
    }
}
