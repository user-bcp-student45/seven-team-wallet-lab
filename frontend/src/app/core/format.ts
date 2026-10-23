const MONEY_INPUT = /^\d{1,7}([.,]\d{1,2})?$/;

/** Normaliza "20", "20,5" o "20.50" a una cadena decimal exacta con 2 decimales, o null si es invalida. */
export function normalizeAmount(raw: string): string | null {
  const value = raw.trim();
  if (!MONEY_INPUT.test(value)) {
    return null;
  }
  const [whole, fraction = ''] = value.replace(',', '.').split('.');
  const amount = `${whole}.${fraction.padEnd(2, '0')}`;
  return Number(amount) > 0 ? amount : null;
}

export function formatPen(amount: string): string {
  return `S/ ${amount}`;
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0].toUpperCase())
    .join('');
}

const dateTime = new Intl.DateTimeFormat('es-PE', { dateStyle: 'medium', timeStyle: 'short' });

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}
