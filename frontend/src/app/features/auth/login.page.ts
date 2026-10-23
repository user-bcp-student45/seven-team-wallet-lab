import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { SessionService } from '../../core/session.service';
import { LogoComponent } from '../../shared/logo.component';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink, LogoComponent],
  template: `
    <main class="screen">
      <header class="topbar"><app-logo /></header>

      <h1 class="title">Inicia sesión</h1>
      <p class="muted">El pago que siempre sabes si se hizo.</p>

      <form class="stack" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="field">
          <label for="email">Correo</label>
          <input id="email" type="email" formControlName="email" autocomplete="username" inputmode="email" />
        </div>
        <div class="field">
          <label for="password">Clave</label>
          <input id="password" type="password" formControlName="password" autocomplete="current-password" />
        </div>

        @if (error()) {
          <p class="alert" role="alert">{{ error() }}</p>
        }

        <button class="btn btn-primary" type="submit" [disabled]="form.invalid || busy()">
          {{ busy() ? 'Ingresando…' : 'Ingresar' }}
        </button>
      </form>

      <p class="muted center">¿Aún no tienes wallet? <a routerLink="/onboarding">Crear cuenta</a></p>
    </main>
  `,
})
export class LoginPage {
  private readonly fb = inject(FormBuilder);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.maxLength(254)]],
    password: ['', [Validators.required, Validators.maxLength(128)]],
  });

  protected async submit(): Promise<void> {
    if (this.form.invalid || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const { email, password } = this.form.getRawValue();
      const user = await this.session.login(email, password);
      await this.router.navigate([user.status === 'ACTIVE' ? '/app' : '/onboarding']);
    } catch (error) {
      this.error.set(messageOf(error));
    } finally {
      this.form.controls.password.reset('');
      this.busy.set(false);
    }
  }
}
