import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { fieldErrorsOf, messageOf } from '../../core/errors';
import { Onboarding } from '../../core/models';
import { SessionService } from '../../core/session.service';
import { LogoComponent } from '../../shared/logo.component';

const TITLES = ['Crea tu cuenta', 'Cuéntanos quién eres', 'Verifica tu celular', 'Activa tu wallet'];
const STEP_NAMES = ['Cuenta', 'Datos personales', 'Verificación de celular', 'Activación'];

function lettersAndDigits(control: AbstractControl): ValidationErrors | null {
  const value = String(control.value ?? '');
  return /[A-Za-z\u00C0-\u024F]/.test(value) && /\d/.test(value) ? null : { lettersAndDigits: true };
}

/** HU-01: onboarding guiado de 4 pasos con paso actual siempre visible y validacion accionable. */
@Component({
  selector: 'app-onboarding',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, LogoComponent, NgTemplateOutlet],
  template: `
    <main class="screen">
      <header class="topbar">
        <app-logo />
        @if (!session.user()) {
          <a class="link-small" routerLink="/login">Ya tengo cuenta</a>
        }
      </header>

      <p class="step-label" aria-live="polite">Paso {{ step() }} de 4 · {{ stepName() }}</p>
      <div class="progress" role="progressbar" aria-label="Progreso del registro"
           [attr.aria-valuenow]="step()" aria-valuemin="1" aria-valuemax="4">
        @for (n of [1, 2, 3, 4]; track n) {
          <span class="progress-seg" [class.on]="n <= step()"></span>
        }
      </div>

      <h1 class="title">{{ title() }}</h1>

      @switch (step()) {
        @case (1) {
          <form class="stack" [formGroup]="account" (ngSubmit)="register()" novalidate>
            <div class="field">
              <label for="email">Correo</label>
              <input id="email" type="email" formControlName="email" autocomplete="username" inputmode="email"
                     [class.invalid]="showError('email', account.controls.email)" />
              @if (showError('email', account.controls.email)) {
                <p class="field-error" role="alert">{{ errorOf('email', account.controls.email) }}</p>
              }
            </div>
            <div class="field">
              <label for="password">Clave</label>
              <input id="password" type="password" formControlName="password" autocomplete="new-password"
                     [class.invalid]="showError('password', account.controls.password)" />
              <p class="hint">Mínimo 10 caracteres, con letras y números.</p>
              @if (showError('password', account.controls.password)) {
                <p class="field-error" role="alert">{{ errorOf('password', account.controls.password) }}</p>
              }
            </div>
            <ng-container *ngTemplateOutlet="footer" />
          </form>
        }
        @case (2) {
          <form class="stack" [formGroup]="personal" (ngSubmit)="savePersonal()" novalidate>
            <div class="field">
              <label for="fullName">Nombres</label>
              <input id="fullName" type="text" formControlName="fullName" autocomplete="name"
                     [class.invalid]="showError('fullName', personal.controls.fullName)" />
              @if (showError('fullName', personal.controls.fullName)) {
                <p class="field-error" role="alert">{{ errorOf('fullName', personal.controls.fullName) }}</p>
              }
            </div>
            <div class="field">
              <label for="documentNumber">DNI</label>
              <input id="documentNumber" type="text" inputmode="numeric" maxlength="8" formControlName="documentNumber"
                     autocomplete="off" [class.invalid]="showError('documentNumber', personal.controls.documentNumber)" />
              @if (showError('documentNumber', personal.controls.documentNumber)) {
                <p class="field-error" role="alert">{{ errorOf('documentNumber', personal.controls.documentNumber) }}</p>
              }
            </div>
            <ng-container *ngTemplateOutlet="footer" />
          </form>
        }
        @case (3) {
          <div class="stack">
            <form class="stack" [formGroup]="phoneForm" (ngSubmit)="sendCode()" novalidate>
              <div class="field">
                <label for="phone">Celular</label>
                <div class="input-prefix">
                  <span aria-hidden="true">+51</span>
                  <input id="phone" type="tel" inputmode="numeric" maxlength="9" formControlName="phone"
                         autocomplete="tel-national" aria-describedby="phone-hint"
                         [class.invalid]="showError('phone', phoneForm.controls.phone)" />
                </div>
                <p id="phone-hint" class="hint">9 dígitos, empieza con 9.</p>
                @if (showError('phone', phoneForm.controls.phone)) {
                  <p class="field-error" role="alert">{{ errorOf('phone', phoneForm.controls.phone) }}</p>
                }
              </div>
              <button class="btn btn-secondary" type="submit" [disabled]="phoneForm.invalid || busy()">
                {{ codeSent() ? 'Reenviar código' : 'Enviar código' }}
              </button>
            </form>

            @if (codeSent()) {
              <form class="stack" [formGroup]="codeForm" (ngSubmit)="verifyCode()" novalidate>
                @if (demoCode()) {
                  <p class="notice" role="status">
                    Sin proveedor SMS en el MVP. Código de demostración:
                    <strong class="mono">{{ demoCode() }}</strong>
                  </p>
                }
                <div class="field">
                  <label for="code">Código de 6 dígitos</label>
                  <input id="code" type="text" inputmode="numeric" maxlength="6" formControlName="code"
                         autocomplete="one-time-code" [class.invalid]="showError('code', codeForm.controls.code)" />
                  @if (showError('code', codeForm.controls.code)) {
                    <p class="field-error" role="alert">{{ errorOf('code', codeForm.controls.code) }}</p>
                  }
                </div>
                <ng-container *ngTemplateOutlet="footer" />
              </form>
            }
            @if (!codeSent()) {
              <p class="hint">Siguiente: Activación</p>
            }
          </div>
        }
        @case (4) {
          <form class="stack" (ngSubmit)="activate()" novalidate>
            <section class="card" aria-label="Resumen">
              <p class="muted small">Revisa tu registro</p>
              <p class="strong">{{ data()?.fullName }}</p>
              <p class="muted">Celular {{ data()?.phoneMasked }}</p>
              <p class="muted small">Saldo inicial ficticio de demostración al activar.</p>
            </section>
            <label class="check">
              <input type="checkbox" [checked]="terms()" (change)="terms.set(!terms())" />
              <span>Acepto los términos del servicio de demostración.</span>
            </label>
            @if (error()) {
              <p class="alert" role="alert">{{ error() }}</p>
            }
            <button class="btn btn-primary" type="submit" [disabled]="!terms() || busy()">
              {{ busy() ? 'Activando…' : 'Activar wallet' }}
            </button>
          </form>
        }
      }
    </main>

    <ng-template #footer>
      @if (error()) {
        <p class="alert" role="alert">{{ error() }}</p>
      }
      @if (nextLabel()) {
        <p class="hint">Siguiente: {{ nextLabel() }}</p>
      }
      <button class="btn btn-primary" type="submit" [disabled]="!currentFormValid() || busy()">
        {{ busy() ? 'Guardando…' : 'Continuar' }}
      </button>
    </ng-template>
  `,
})
export class OnboardingPage implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionService);

  protected readonly data = signal<Onboarding | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly serverErrors = signal<Record<string, string>>({});
  protected readonly codeSent = signal(false);
  protected readonly demoCode = signal<string | null>(null);
  protected readonly terms = signal(false);

  protected readonly step = computed(() => this.data()?.currentStep ?? 1);
  protected readonly title = computed(() => TITLES[this.step() - 1]);
  protected readonly stepName = computed(() => STEP_NAMES[this.step() - 1]);
  protected readonly nextLabel = computed(() => STEP_NAMES[this.step()] ?? '');

  protected readonly account = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.minLength(10), Validators.maxLength(128), lettersAndDigits]],
  });
  protected readonly personal = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120),
      Validators.pattern(/^[A-Za-z\u00C0-\u024F][A-Za-z\u00C0-\u024F .'-]*$/)]],
    documentNumber: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
  });
  protected readonly phoneForm = this.fb.nonNullable.group({
    phone: ['', [Validators.required, Validators.pattern(/^9\d{8}$/)]],
  });
  protected readonly codeForm = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  async ngOnInit(): Promise<void> {
    const user = this.session.user();
    if (!user) {
      return;
    }
    if (user.status === 'ACTIVE') {
      await this.router.navigate(['/app']);
      return;
    }
    await this.load();
  }

  protected currentFormValid(): boolean {
    switch (this.step()) {
      case 1: return this.account.valid;
      case 2: return this.personal.valid;
      case 3: return this.codeForm.valid;
      default: return true;
    }
  }

  protected showError(name: string, control: AbstractControl): boolean {
    return (control.invalid && (control.dirty || control.touched)) || !!this.serverErrors()[name];
  }

  protected errorOf(name: string, control: AbstractControl): string {
    const server = this.serverErrors()[name];
    if (server) {
      return server;
    }
    const e = control.errors ?? {};
    switch (name) {
      case 'email': return e['required'] ? 'Ingresa tu correo.' : 'Ingresa un correo válido, por ejemplo nombre@correo.com.';
      case 'password': return e['required'] ? 'Crea una clave para continuar.'
        : e['minlength'] ? 'La clave debe tener al menos 10 caracteres.' : 'La clave debe incluir letras y números.';
      case 'fullName': return e['required'] ? 'Ingresa tus nombres.' : 'Usa solo letras en tus nombres.';
      case 'documentNumber': return 'El DNI debe tener 8 dígitos.';
      case 'phone': return 'El celular debe tener 9 dígitos y empezar con 9.';
      default: return 'El código tiene 6 dígitos.';
    }
  }

  protected async register(): Promise<void> {
    await this.run(async () => {
      const { email, password } = this.account.getRawValue();
      this.data.set(await firstValueFrom(this.api.register(email, password)));
      await this.session.refreshUser();
      this.account.controls.password.reset('');
    });
  }

  protected async savePersonal(): Promise<void> {
    await this.run(async () => {
      const { fullName, documentNumber } = this.personal.getRawValue();
      this.data.set(await firstValueFrom(this.api.savePersonalData(fullName, documentNumber)));
    });
  }

  protected async sendCode(): Promise<void> {
    await this.run(async () => {
      const issued = await firstValueFrom(this.api.requestPhoneCode(this.phoneForm.getRawValue().phone));
      this.codeSent.set(true);
      this.demoCode.set(issued.demoCode ?? null);
    });
  }

  protected async verifyCode(): Promise<void> {
    await this.run(async () => {
      this.data.set(await firstValueFrom(this.api.verifyPhone(this.codeForm.getRawValue().code)));
    });
  }

  protected async activate(): Promise<void> {
    if (!this.terms()) {
      return;
    }
    await this.run(async () => {
      this.data.set(await firstValueFrom(this.api.activate()));
      await this.session.refreshUser();
      await this.session.refreshBalance();
      await this.router.navigate(['/app'], { queryParams: { welcome: 1 } });
    });
  }

  private async load(): Promise<void> {
    try {
      this.data.set(await firstValueFrom(this.api.onboarding()));
    } catch (error) {
      this.error.set(messageOf(error));
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.serverErrors.set({});
    try {
      await action();
    } catch (error) {
      this.error.set(messageOf(error));
      this.serverErrors.set(Object.fromEntries(fieldErrorsOf(error).map((f) => [f.field, f.message])));
    } finally {
      this.busy.set(false);
    }
  }
}
