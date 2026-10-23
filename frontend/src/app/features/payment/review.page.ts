import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { initials } from '../../core/format';
import { PaymentFlowService } from '../../core/payment-flow.service';

/** Revision previa (HU-02 CA1): destino verificado y monto antes de confirmar. */
@Component({
  selector: 'app-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (flow.intent(); as intent) {
      <p class="muted center">Revisa antes de confirmar</p>

      <section class="card" aria-label="Destino">
        <div class="row">
          <span class="avatar avatar-blue" aria-hidden="true">{{ initialsOf(intent.destination.displayName) }}</span>
          <div class="contact-text">
            <span class="strong">{{ intent.destination.displayName }}</span>
            <span class="muted small">Celular {{ intent.destination.phoneMasked }}</span>
          </div>
        </div>
        @if (intent.destination.verified) {
          <span class="badge badge-ok"><span class="dot"></span>Destino verificado</span>
        }
      </section>

      <section class="amount-block" aria-label="Monto">
        <span class="muted">Monto</span>
        <span class="amount">S/ {{ intent.amount }}</span>
        <span class="muted small">Sin comisión · Llega al instante</span>
      </section>

      <p class="muted small center" [class.warn]="remaining() === 0" aria-live="polite">
        @if (remaining() > 0) {
          Confirma en {{ remainingLabel() }}
        } @else {
          El tiempo de este pago venció. Si confirmas, se mostrará como expirado y no se cobrará.
        }
      </p>

      @if (flow.phase() === 'uncertain') {
        <section class="notice" role="status">
          <p class="strong">Estamos confirmando tu pago</p>
          <p class="small">No cierres esta pantalla. No se cobrará dos veces: usamos la misma clave de seguridad.</p>
          @if (flow.notice()) {
            <p class="small">{{ flow.notice() }}</p>
          }
          <div class="row-gap">
            <button class="btn btn-secondary" type="button" (click)="check()">Consultar resultado</button>
            <button class="btn btn-primary" type="button" (click)="confirm()">Reintentar con seguridad</button>
          </div>
        </section>
      } @else {
        @if (flow.error()) {
          <p class="alert" role="alert">{{ flow.error() }}</p>
        }
        <button class="btn btn-primary" type="button" (click)="confirm()" [disabled]="flow.phase() === 'sending'">
          {{ flow.phase() === 'sending' ? 'Confirmando…' : 'Confirmar pago' }}
        </button>
        <button class="btn btn-secondary" type="button" (click)="edit()" [disabled]="flow.phase() === 'sending'">Editar</button>
      }
    }
  `,
})
export class ReviewPage implements OnInit {
  protected readonly flow = inject(PaymentFlowService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private readonly now = signal(Date.now());
  protected readonly remaining = computed(() => {
    const intent = this.flow.intent();
    return intent ? Math.max(0, Math.ceil((new Date(intent.expiresAt).getTime() - this.now()) / 1000)) : 0;
  });
  protected readonly remainingLabel = computed(() => {
    const total = this.remaining();
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
  });

  ngOnInit(): void {
    if (!this.flow.intent()) {
      void this.router.navigate(['/app']);
      return;
    }
    const timer = setInterval(() => this.now.set(Date.now()), 1000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected async confirm(): Promise<void> {
    await this.flow.confirm();
    await this.goToResultIfAny();
  }

  protected async check(): Promise<void> {
    await this.flow.recover();
    await this.goToResultIfAny();
  }

  protected edit(): Promise<boolean> {
    return this.router.navigate(['/app']);
  }

  private async goToResultIfAny(): Promise<void> {
    if (this.flow.result()) {
      await this.router.navigate(['/app/result']);
    }
  }
}
