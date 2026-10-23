import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { isUncertain, messageOf, toApiError } from './errors';
import { Contact, Intent, Payment } from './models';
import { SessionService } from './session.service';

export type FlowPhase = 'idle' | 'creating' | 'sending' | 'uncertain';

/**
 * Flujo HU-02/HU-04: revisar, confirmar y recuperar el resultado.
 * La clave de idempotencia es la del intento y se reutiliza en cada reenvio; un timeout nunca se
 * interpreta como fallo ni crea un nuevo cobro.
 */
@Injectable({ providedIn: 'root' })
export class PaymentFlowService {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);

  readonly contact = signal<Contact | null>(null);
  readonly intent = signal<Intent | null>(null);
  readonly result = signal<Payment | null>(null);
  readonly phase = signal<FlowPhase>('idle');
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);

  /** Pantalla de revision: crea el intento sin mover fondos. */
  async prepare(contact: Contact, amount: string): Promise<boolean> {
    this.result.set(null);
    this.error.set(null);
    this.notice.set(null);
    this.phase.set('creating');
    try {
      this.intent.set(await firstValueFrom(this.api.createIntent(contact.id, amount)));
      this.contact.set(contact);
      return true;
    } catch (error) {
      this.error.set(messageOf(error));
      return false;
    } finally {
      this.phase.set('idle');
    }
  }

  async confirm(): Promise<void> {
    const intent = this.intent();
    if (!intent || this.phase() === 'sending') {
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.phase.set('sending');
    try {
      this.finish(await firstValueFrom(this.api.confirm(intent.intentId, intent.idempotencyKey)));
    } catch (error) {
      if (isUncertain(error)) {
        this.phase.set('uncertain');
        await this.recover();
      } else {
        this.phase.set('idle');
        this.error.set(messageOf(error));
      }
    }
  }

  /** Consulta el resultado original del intento; no inicia otro pago. */
  async recover(attempts = 4, delayMs = 1500): Promise<void> {
    const intent = this.intent();
    if (!intent) {
      return;
    }
    this.phase.set('uncertain');
    for (let i = 0; i < attempts; i++) {
      try {
        this.finish(await firstValueFrom(this.api.intentResult(intent.intentId)));
        return;
      } catch (error) {
        if (toApiError(error)?.code === 'NO_RESULT_YET') {
          this.notice.set('No se registró ningún cobro. Puedes reintentar con seguridad: no se duplicará el pago.');
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, delayMs));
      }
    }
    this.notice.set('Seguimos confirmando tu pago. No se cobrará dos veces; consulta de nuevo en unos segundos.');
  }

  /** Tras un resultado no exitoso: nuevo intento con otra clave, nunca reutilizando la anterior. */
  async retryWithNewIntent(): Promise<boolean> {
    const contact = this.contact();
    const intent = this.intent();
    if (!contact || !intent) {
      return false;
    }
    return this.prepare(contact, intent.amount);
  }

  reset(): void {
    this.contact.set(null);
    this.intent.set(null);
    this.result.set(null);
    this.error.set(null);
    this.notice.set(null);
    this.phase.set('idle');
  }

  private finish(payment: Payment): void {
    this.result.set(payment);
    this.phase.set('idle');
    this.notice.set(null);
    if (payment.status === 'SUCCESS') {
      void this.session.refreshBalance();
    }
  }
}
