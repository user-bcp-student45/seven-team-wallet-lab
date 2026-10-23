import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody, FieldError } from './models';

export function toApiError(error: unknown): ApiErrorBody | null {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    const body = error.error as Partial<ApiErrorBody>;
    if (typeof body.code === 'string' && typeof body.message === 'string') {
      return body as ApiErrorBody;
    }
  }
  return null;
}

export function fieldErrorsOf(error: unknown): FieldError[] {
  return toApiError(error)?.fieldErrors ?? [];
}

/** Mensaje seguro y accionable; nunca muestra detalles internos. */
export function messageOf(error: unknown): string {
  const api = toApiError(error);
  if (api) {
    return api.message;
  }
  if (error instanceof HttpErrorResponse && error.status === 0) {
    return 'No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo.';
  }
  return 'Ocurrió un error inesperado. Inténtalo de nuevo.';
}

/** Un fallo de transporte o 5xx no demuestra que el pago fallo: el resultado es incierto. */
export function isUncertain(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500);
}
