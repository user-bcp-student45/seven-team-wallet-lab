package com.seventeam.wallet.identity.adapter.in;

import com.seventeam.wallet.identity.application.AuthService;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.shared.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(
            @NotBlank(message = "Ingresa tu correo.") @Size(max = 254, message = "El correo es demasiado largo.") String email,
            @NotBlank(message = "Ingresa tu clave.") @Size(max = 128, message = "La clave es demasiado larga.") String password) {}

    record SessionView(UUID userId, String email, String fullName, String status, int completedSteps) {
        static SessionView of(User user) {
            return new SessionView(user.id(), user.email(), user.fullName(), user.status().name(),
                    user.completedSteps());
        }
    }

    private final AuthService auth;
    private final SessionSupport sessions;

    AuthController(AuthService auth, SessionSupport sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    SessionView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        User user = auth.authenticate(body.email().trim(), body.password());
        sessions.establish(user.id(), user.email(), request, response);
        return SessionView.of(user);
    }

    @GetMapping("/me")
    SessionView me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return SessionView.of(auth.current(principal.userId()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }
}
