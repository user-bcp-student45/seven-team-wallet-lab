import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionService } from '../../core/session.service';
import { PaymentFlowService } from '../../core/payment-flow.service';
import { LogoComponent } from '../../shared/logo.component';

/** Estructura de la app activa: saldo ocultable, pestanas Enviar/Cobrar/Pagar y navegacion. */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LogoComponent],
  template: `
    <main class="screen">
      <header class="topbar">
        <app-logo />
        <button type="button" class="pill" (click)="visible.set(!visible())" [attr.aria-pressed]="visible()"
                aria-label="Mostrar u ocultar saldo">
          S/ {{ visible() ? (session.balance() ?? '--') : '••' }}
        </button>
      </header>

      <nav class="tabs" aria-label="Operaciones">
        <button type="button" class="tab" disabled title="Próximamente">Enviar</button>
        <button type="button" class="tab" disabled title="Próximamente (cobros con QR, HU-03 diferida)">Cobrar</button>
        <a class="tab active" routerLink="/app" aria-current="page">Pagar</a>
      </nav>

      <router-outlet />

      <nav class="bottom" aria-label="Principal">
        <a routerLink="/app" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Inicio</a>
        <a routerLink="/app/history" routerLinkActive="active">Historial</a>
        <button type="button" (click)="logout()">Salir</button>
      </nav>
    </main>
  `,
})
export class ShellPage implements OnInit {
  protected readonly session = inject(SessionService);
  private readonly flow = inject(PaymentFlowService);
  private readonly router = inject(Router);

  protected readonly visible = signal(true);

  ngOnInit(): void {
    void this.session.refreshBalance();
  }

  protected async logout(): Promise<void> {
    this.flow.reset();
    await this.session.logout();
    await this.router.navigate(['/login']);
  }
}
