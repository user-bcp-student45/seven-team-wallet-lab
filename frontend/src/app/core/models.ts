export interface FieldError {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  fieldErrors?: FieldError[];
  requestId?: string;
}

export type UserStatus = 'ONBOARDING' | 'ACTIVE';

export interface SessionUser {
  userId: string;
  email: string;
  fullName?: string;
  status: UserStatus;
  completedSteps: number;
}

export interface OnboardingStep {
  number: number;
  title: string;
  state: 'DONE' | 'CURRENT' | 'PENDING';
}

export interface Onboarding {
  status: UserStatus;
  completedSteps: number;
  currentStep: number;
  totalSteps: number;
  steps: OnboardingStep[];
  fullName?: string;
  phoneMasked?: string;
}

export interface PhoneCode {
  expiresAt: string;
  demoCode?: string;
}

export interface Contact {
  id: string;
  displayName: string;
  phoneMasked: string;
}

export interface Wallet {
  balance: string;
  currency: string;
}

export interface Intent {
  intentId: string;
  destination: { contactId: string; displayName: string; phoneMasked: string; verified: boolean };
  amount: string;
  currency: string;
  fee: string;
  expiresAt: string;
  idempotencyKey: string;
}

export type PaymentStatus = 'SUCCESS' | 'FAILED' | 'EXPIRED';

export interface Payment {
  paymentId: string;
  reference: string;
  status: PaymentStatus;
  failureCode?: string;
  message?: string;
  amount: string;
  currency: string;
  destinationName: string;
  createdAt: string;
}

export interface Movement {
  paymentId: string;
  reference: string;
  direction: 'SENT' | 'RECEIVED';
  counterpartName: string;
  amount: string;
  currency: string;
  status: PaymentStatus;
  createdAt: string;
}
