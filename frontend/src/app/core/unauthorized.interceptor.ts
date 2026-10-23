import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { SessionService } from './session.service';

const PUBLIC_PATHS = ['/api/auth/login', '/api/auth/me', '/api/onboarding/register'];

/** Una sesion vencida o invalida (401) devuelve al inicio de sesion. */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(SessionService);
  const router = inject(Router);
  return next(req).pipe(
    tap({
      error: (error: unknown) => {
        if (
          error instanceof HttpErrorResponse &&
          error.status === 401 &&
          !PUBLIC_PATHS.some((path) => req.url.startsWith(path))
        ) {
          session.clear();
          void router.navigate(['/login']);
        }
      },
    }),
  );
};
