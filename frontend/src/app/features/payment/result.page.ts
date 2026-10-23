import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { formatDateTime } from '../../core/format';
import { PaymentFlowService } from '../../core/payment-flow.service';

/** Resultado terminal unico (HU-02 CA2-CA4): exitoso con referencia, o no realizado con siguiente accion. */
@Component({
  selector: 'app-result',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (flow.result(); as payment) {
      @if (payment.status === 'SUCCESS') {
        <section class="result" aria-live="polite">
          <div class="result-icon ok" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="40" height="40"><path d="M5 13l4 4L19 7" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" /></svg>
          </div>
          <h1 class="title center">Pago exitoso</h1>
          <p class="muted center">S/ {{ payment.amount }} a {{ payment.destinationName }}</p>
        </section>

        <section class="card" aria-label="Comprobante">
          <div class="row-between">
            <span class="muted small">Referencia</span>
            <span class="badge badge-ok">Registrada</span>
          </div>
          <p class="mono strong">{{ payment.reference }}</p>
          <p class="muted small">Fecha y hora</p>
          <p>{{ when(payment.createdAt) }}</p>
        </section>

        @if (shareNotice()) {
          <p class="notice" role="status">{{ shareNotice() }}</p>
        }
        <button class="btn btn-blue" type="button" (click)="share(payment.reference, payment.amount, payment.destinationName)">
          Compartir comprobante
        </button>
        <button class="btn btn-secondary" type="button" (click)="home()">Volver al inicio</button>
      } @else {
        <section class="result" aria-live="polite">
          <div class="result-icon warn" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="40" height="40"><path d="M12 6v8M12 18h.01" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" /></svg>
          </div>
          <h1 class="title center">Pago no realizado</h1>
          <p class="muted center">{{ reason(payment.failureCode) }}</p>
        </section>

        <section class="card" aria-label="Estado del cobro">
          <p class="row"><span class="dot ok"></span><span class="strong">No se hizo ningún cobro</span></p>
          <p class="muted small">Puedes reintentar con seguridad, no se duplicará el pago.</p>
        </section>

        @if (flow.error()) {
          <p class="alert" role="alert">{{ flow.error() }}</p>
        }
        <button class="btn btn-primary" type="button" (click)="retry()" [disabled]="busy()">Reintentar</button>
        <button class="btn btn-secondary" type="button" (click)="home()">Volver</button>
      }
    }
  `,
})
export class ResultPage implements OnInit {
  protected readonly flow = inject(PaymentFlowService);
  private readonly router = inject(Router);

  protected readonly shareNotice = signal<string | null>(null);
  protected readonly busy = signal(false);

  ngOnInit(): void {
    if (!this.flow.result()) {
      void this.router.navigate(['/app']);
    }
  }

  protected when(iso: string): string {
    return formatDateTime(iso);
  }

  protected reason(code?: string): string {
    switch (code) {
      case 'INTENT_EXPIRED': return 'El tiempo de espera expiró.';
      case 'INSUFFICIENT_FUNDS': return 'Tu saldo no alcanza para este pago.';
      case 'DESTINATION_UNAVAILABLE': return 'El destino ya no está disponible.';
      default: return 'No pudimos completar el pago.';
    }
  }

  protected async retry(): Promise<void> {
    this.busy.set(true);
    try {
      if (await this.flow.retryWithNewIntent()) {
        await this.router.navigate(['/app/review']);
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async home(): Promise<void> {
    this.flow.reset();
    await this.router.navigate(['/app']);
  }

  protected async share(reference: string, amount: string, destination: string): Promise<void> {
    const text = `Pago de S/ ${amount} a ${destination}. Referencia ${reference}.`;
    try {
      if (typeof navigator.share === 'function') {
        await navigator.share({ title: 'Comprobante Kipu', text });
      } else {
        await navigator.clipboard.writeText(text);
        this.shareNotice.set('Comprobante copiado al portapapeles.');
      }
    } catch {
      this.shareNotice.set('No pudimos compartirlo. La referencia queda en tu historial.');
    }
  }
}
