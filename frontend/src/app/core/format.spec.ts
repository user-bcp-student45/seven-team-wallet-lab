import { normalizeAmount } from './format';

describe('normalizeAmount', () => {
  it('normaliza a una cadena decimal exacta con dos decimales', () => {
    expect(normalizeAmount('20')).toBe('20.00');
    expect(normalizeAmount('20,5')).toBe('20.50');
    expect(normalizeAmount(' 0.10 ')).toBe('0.10');
  });

  it('rechaza importes invalidos', () => {
    for (const value of ['', '0', '0.00', '-5', '1.234', 'abc', '1e3', '12345678']) {
      expect(normalizeAmount(value)).toBeNull();
    }
  });
});
