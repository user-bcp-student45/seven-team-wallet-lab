import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Contact, Intent, Payment } from './models';
import { PaymentFlowService } from './payment-flow.service';

const contact: Contact = { id: 'c1', displayName: 'Carlos Quispe', phoneMasked: '+51 ••• ••• 987' };
const intent: Intent = {
  intentId: 'i1',
  destination: { contactId: 'c1', displayName: 'Carlos Quispe', phoneMasked: '+51 ••• ••• 987', verified: true },
  amount: '20.00',
  currency: 'PEN',
  fee: '0.00',
  expiresAt: '2099-01-01T00:00:00Z',
  idempotencyKey: 'k'.repeat(64),
};
const success: Payment = {
  paymentId: 'p1',
  reference: 'TX-7F3A-91C2',
  status: 'SUCCESS',
  amount: '20.00',
  currency: 'PEN',
  destinationName: 'Carlos Quispe',
  createdAt: '2026-10-23T10:42:00Z',
};

describe('PaymentFlowService', () => {
  let flow: PaymentFlowService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    flow = TestBed.inject(PaymentFlowService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.match(() => true));

  async function prepared(): Promise<void> {
    const done = flow.prepare(contact, '20.00');
    http.expectOne('/api/payments/intents').flush(intent);
    await done;
  }

  it('envia la clave de idempotencia del intento al confirmar', async () => {
    await prepared();
    const done = flow.confirm();
    const req = http.expectOne('/api/payments');
    expect(req.request.headers.get('Idempotency-Key')).toBe(intent.idempotencyKey);
    expect(req.request.body).toEqual({ intentId: 'i1' });
    req.flush(success);
    await done;
    expect(flow.result()?.status).toBe('SUCCESS');
    http.match('/api/wallet');
  });

  it('un corte de red no es un fallo: consulta el resultado y reintenta con la misma clave', async () => {
    await prepared();

    const first = flow.confirm();
    http.expectOne('/api/payments').error(new ProgressEvent('error'), { status: 0 });
    await Promise.resolve();
    http.expectOne('/api/payments/intents/i1/result').flush(
      { status: 404, code: 'NO_RESULT_YET', message: 'sin resultado' },
      { status: 404, statusText: 'Not Found' },
    );
    await first;
    expect(flow.phase()).toBe('uncertain');
    expect(flow.result()).toBeNull();

    const retry = flow.confirm();
    const again = http.expectOne('/api/payments');
    expect(again.request.headers.get('Idempotency-Key')).toBe(intent.idempotencyKey);
    again.flush(success);
    await retry;
    http.expectNone('/api/payments/intents');
    expect(flow.result()?.reference).toBe('TX-7F3A-91C2');
  });

  it('tras un resultado no exitoso el reintento crea un intento nuevo', async () => {
    await prepared();
    const retry = flow.retryWithNewIntent();
    const req = http.expectOne('/api/payments/intents');
    expect(req.request.body).toEqual({ contactId: 'c1', amount: '20.00' });
    req.flush({ ...intent, intentId: 'i2', idempotencyKey: 'n'.repeat(64) });
    await retry;
    expect(flow.intent()?.idempotencyKey).toBe('n'.repeat(64));
  });

  it('un error de negocio (4xx) muestra el mensaje sin marcar el pago como incierto', async () => {
    await prepared();
    const done = flow.confirm();
    http.expectOne('/api/payments').flush(
      { status: 422, code: 'IDEMPOTENCY_KEY_MISMATCH', message: 'La clave no corresponde.' },
      { status: 422, statusText: 'Unprocessable' },
    );
    await done;
    expect(flow.phase()).toBe('idle');
    expect(flow.error()).toBe('La clave no corresponde.');
  });
});
