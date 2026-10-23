package com.seventeam.wallet.identity.adapter.in;

import com.seventeam.wallet.identity.application.AuthService;
import com.seventeam.wallet.identity.application.OnboardingService;
import com.seventeam.wallet.identity.application.OnboardingService.PhoneCodeIssued;
import com.seventeam.wallet.identity.application.OnboardingService.StepView;
import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.shared.AuthenticatedUser;
import com.seventeam.wallet.shared.CorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
class OnboardingController {

    record RegisterRequest(
            @NotBlank(message = "Ingresa tu correo.")
            @Email(message = "Ingresa un correo válido, por ejemplo nombre@correo.com.")
            @Size(max = 254, message = "El correo es demasiado largo.") String email,
            @NotBlank(message = "Crea una clave para continuar.")
            @Size(min = 10, max = 128, message = "La clave debe tener entre 10 y 128 caracteres.") String password) {}

    record PersonalDataRequest(
            @NotBlank(message = "Ingresa tus nombres.")
            @Size(min = 2, max = 120, message = "Los nombres deben tener entre 2 y 120 caracteres.")
            @Pattern(regexp = "^[\\p{L}][\\p{L} .'-]*$", message = "Usa solo letras en tus nombres.") String fullName,
            @NotBlank(message = "Ingresa tu DNI.")
            @Pattern(regexp = "^[0-9]{8}$", message = "El DNI debe tener 8 dígitos.") String documentNumber) {}

    record PhoneRequest(
            @NotBlank(message = "Ingresa tu celular.")
            @Pattern(regexp = "^9[0-9]{8}$", message = "El celular debe tener 9 dígitos y empezar con 9.") String phone) {}

    record PhoneCodeRequest(
            @NotBlank(message = "Ingresa el código.")
            @Pattern(regexp = "^[0-9]{6}$", message = "El código tiene 6 dígitos.") String code) {}

    record ActivateRequest(@AssertTrue(message = "Acepta los términos para activar tu wallet.") boolean acceptTerms) {}

    record OnboardingView(String status, int completedSteps, int currentStep, int totalSteps, List<StepView> steps,
                          String fullName, String phoneMasked) {}

    record PhoneCodeView(Instant expiresAt, String demoCode) {}

    private final OnboardingService onboarding;
    private final AuthService auth;
    private final SessionSupport sessions;

    OnboardingController(OnboardingService onboarding, AuthService auth, SessionSupport sessions) {
        this.onboarding = onboarding;
        this.auth = auth;
        this.sessions = sessions;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    OnboardingView register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
                            HttpServletResponse response) {
        User user = onboarding.register(body.email(), body.password(), correlation(request));
        sessions.establish(user.id(), user.email(), request, response);
        return view(user);
    }

    @GetMapping
    OnboardingView status(@AuthenticationPrincipal AuthenticatedUser principal) {
        return view(auth.current(principal.userId()));
    }

    @PostMapping("/personal-data")
    OnboardingView personalData(@AuthenticationPrincipal AuthenticatedUser principal,
                                @Valid @RequestBody PersonalDataRequest body, HttpServletRequest request) {
        return view(onboarding.submitPersonalData(principal.userId(), body.fullName(), body.documentNumber(),
                correlation(request)));
    }

    @PostMapping("/phone/code")
    PhoneCodeView requestCode(@AuthenticationPrincipal AuthenticatedUser principal,
                              @Valid @RequestBody PhoneRequest body) {
        PhoneCodeIssued issued = onboarding.requestPhoneCode(principal.userId(), body.phone());
        return new PhoneCodeView(issued.expiresAt(), issued.demoCode());
    }

    @PostMapping("/phone/verify")
    OnboardingView verifyPhone(@AuthenticationPrincipal AuthenticatedUser principal,
                               @Valid @RequestBody PhoneCodeRequest body, HttpServletRequest request) {
        return view(onboarding.verifyPhone(principal.userId(), body.code(), correlation(request)));
    }

    @PostMapping("/activate")
    OnboardingView activate(@AuthenticationPrincipal AuthenticatedUser principal,
                            @Valid @RequestBody ActivateRequest body, HttpServletRequest request) {
        return view(onboarding.activate(principal.userId(), correlation(request)));
    }

    private OnboardingView view(User user) {
        int current = user.isActive() ? OnboardingService.TOTAL_STEPS
                : Math.min(user.completedSteps() + 1, OnboardingService.TOTAL_STEPS);
        return new OnboardingView(user.status().name(), user.completedSteps(), current,
                OnboardingService.TOTAL_STEPS, onboarding.steps(user), user.fullName(), user.maskedPhone());
    }

    private static String correlation(HttpServletRequest request) {
        return CorrelationFilter.contextOf(request).correlationId();
    }
}
