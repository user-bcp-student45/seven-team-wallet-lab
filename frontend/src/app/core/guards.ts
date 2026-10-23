import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** La raiz envia a cada usuario al punto de entrada que le corresponde. */
export const entryGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);
  const user = session.user();
  if (!user) {
    return router.createUrlTree(['/login']);
  }
  return router.createUrlTree([user.status === 'ACTIVE' ? '/app' : '/onboarding']);
};

export const guestGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);
  const user = session.user();
  if (!user) {
    return true;
  }
  return router.createUrlTree([user.status === 'ACTIVE' ? '/app' : '/onboarding']);
};

/** La UI no es frontera de autorizacion: el servidor valida igualmente cada operacion. */
export const activeGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);
  const user = session.user();
  if (!user) {
    return router.createUrlTree(['/login']);
  }
  return user.status === 'ACTIVE' ? true : router.createUrlTree(['/onboarding']);
};
