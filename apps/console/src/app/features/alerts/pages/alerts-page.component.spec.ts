import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import type { Alert } from '../models/alert.model';
import { AlertsService } from '../services/alerts.service';
import { AlertsPageComponent } from './alerts-page.component';

const NOW = Date.now();

// Mirrors the template's `date: 'dd/MM/yyyy HH:mm'` pipe using the runtime's
// own local timezone (via plain Date getters, same as DatePipe's default
// timezone) instead of hardcoding an assumed UTC offset -- this repo
// registers no LOCALE_ID/timezone anywhere (T4's own finding), so asserting
// a fixed local-time string here would only pass on a machine/CI whose local
// timezone happens to match the one this was written under.
function formatLocal(iso: string): string {
  const date = new Date(iso);
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  return `${day}/${month}/${date.getFullYear()} ${hours}:${minutes}`;
}

// Explicit, distinct, newest-first timestamps -- deterministic input for the
// day-group sort, instead of three `new Date()` calls that could tie or
// drift by a few ms depending on how fast the test runs.
const ALERTS: Alert[] = [
  {
    id: 'a1',
    vehicleId: 'VH-1042',
    vehicleLabel: 'Camión 04',
    type: 'geofence_enter',
    detail: 'Entró en la geocerca "Puerto de Valencia"',
    occurredAt: new Date(NOW).toISOString(),
    acknowledged: false,
    acknowledgedAt: null,
    acknowledgedBy: null,
  },
  {
    id: 'a2',
    vehicleId: 'VH-0892',
    vehicleLabel: 'Furgoneta 02',
    type: 'speeding',
    detail: '92 km/h en una zona con límite de 60 km/h',
    occurredAt: new Date(NOW - 60_000).toISOString(),
    acknowledged: false,
    acknowledgedAt: null,
    acknowledgedBy: null,
  },
  {
    id: 'a3',
    vehicleId: 'VH-1107',
    vehicleLabel: 'Camión 11',
    type: 'excessive_idle',
    detail: '22 min detenido con el motor en marcha',
    occurredAt: new Date(NOW - 120_000).toISOString(),
    acknowledged: true,
    acknowledgedAt: '2026-01-01T09:05:00.000Z',
    acknowledgedBy: 'dispatcher@acme.test',
  },
];

describe('AlertsPageComponent', () => {
  let alertsService: { list: ReturnType<typeof vi.fn>; acknowledge: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    alertsService = { list: vi.fn(() => of(ALERTS)), acknowledge: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: AlertsService, useValue: alertsService }],
    });
  });

  function createFixture() {
    const fixture = TestBed.createComponent(AlertsPageComponent);
    fixture.detectChanges();
    return fixture;
  }

  function cardIds(fixture: ReturnType<typeof createFixture>): (string | null)[] {
    return fixture.debugElement
      .queryAll(By.css('.alert-card'))
      .map((el) => (el.nativeElement as HTMLElement).getAttribute('data-testid'));
  }

  it('loads alerts on init and renders one card per alert', () => {
    const fixture = createFixture();

    expect(alertsService.list).toHaveBeenCalledTimes(1);
    expect(cardIds(fixture)).toEqual(['alert-a1', 'alert-a2', 'alert-a3']);
  });

  it('shows the real unacknowledged/total count from the store, not a hardcoded value', () => {
    const fixture = createFixture();

    const sub: HTMLElement = fixture.nativeElement.querySelector('.page-sub');
    expect(sub.textContent).toContain('2 sin atender de 3');
  });

  it('clicking a type filter chip narrows the rendered cards and marks it active', () => {
    const fixture = createFixture();

    const chip = fixture.debugElement.query(By.css('[data-testid="alert-filter-speeding"]'));
    chip.triggerEventHandler('click', undefined);
    fixture.detectChanges();

    expect(cardIds(fixture)).toEqual(['alert-a2']);
    expect((chip.nativeElement as HTMLElement).classList.contains('on')).toBe(true);
  });

  it('typing in the search box narrows the rendered cards by vehicle', () => {
    const fixture = createFixture();

    const input: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="alert-search"]');
    input.value = 'furgoneta';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(cardIds(fixture)).toEqual(['alert-a2']);
  });

  it('sorts same-day alerts newest-first regardless of the order the service returns them in', () => {
    alertsService.list.mockReturnValue(of([...ALERTS].reverse()));

    const fixture = createFixture();

    expect(cardIds(fixture)).toEqual(['alert-a1', 'alert-a2', 'alert-a3']);
  });

  it('renders visually distinct icons for geofence_enter and geofence_exit', () => {
    const enterAlert: Alert = { ...ALERTS[0], id: 'enter', type: 'geofence_enter' };
    const exitAlert: Alert = { ...ALERTS[0], id: 'exit', type: 'geofence_exit' };
    alertsService.list.mockReturnValue(of([enterAlert, exitAlert]));

    const fixture = createFixture();

    const enterSvg = fixture.nativeElement.querySelector('[data-testid="alert-enter"] .alert-icon svg')?.outerHTML;
    const exitSvg = fixture.nativeElement.querySelector('[data-testid="alert-exit"] .alert-icon svg')?.outerHTML;
    expect(enterSvg).not.toBe(exitSvg);
  });

  it('marks the active filter chip with aria-pressed', () => {
    const fixture = createFixture();

    const chip = fixture.debugElement.query(By.css('[data-testid="alert-filter-speeding"]'));
    expect(chip.nativeElement.getAttribute('aria-pressed')).toBe('false');

    chip.triggerEventHandler('click', undefined);
    fixture.detectChanges();

    expect(chip.nativeElement.getAttribute('aria-pressed')).toBe('true');
  });

  it('combines the active filter chip and the search query', () => {
    const fixture = createFixture();

    fixture.debugElement.query(By.css('[data-testid="alert-filter-geofence"]')).triggerEventHandler('click', undefined);
    fixture.detectChanges();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="alert-search"]');
    input.value = 'furgoneta';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(cardIds(fixture)).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('No hay alertas que coincidan con el filtro.');
  });

  it('shows a mark-as-attended button only for unacknowledged alerts', () => {
    const fixture = createFixture();

    expect(fixture.nativeElement.querySelector('[data-testid="alert-acknowledge-a1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="alert-acknowledge-a2"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="alert-acknowledge-a3"]')).toBeNull();
  });

  it('clicking the mark-as-attended button acknowledges that alert and shows who/when', () => {
    const acknowledged: Alert = {
      ...ALERTS[0],
      acknowledged: true,
      acknowledgedAt: '2026-01-01T10:45:00.000Z',
      acknowledgedBy: 'dispatcher@acme.test',
    };
    alertsService.acknowledge.mockReturnValue(of(acknowledged));
    const fixture = createFixture();

    const button = fixture.debugElement.query(By.css('[data-testid="alert-acknowledge-a1"]'));
    button.triggerEventHandler('click', undefined);
    fixture.detectChanges();

    expect(alertsService.acknowledge).toHaveBeenCalledWith('a1');
    expect(fixture.nativeElement.querySelector('[data-testid="alert-acknowledge-a1"]')).toBeNull();
    const card = fixture.debugElement.query(By.css('[data-testid="alert-a1"]'));
    expect(card.nativeElement.textContent).toContain('Atendida por dispatcher@acme.test');
    expect(card.nativeElement.textContent).toContain(formatLocal('2026-01-01T10:45:00.000Z'));
  });

  it('shows just "Atendida" (no fabricated author/time) for a legacy alert acknowledged before the audit columns existed', () => {
    const legacyAcknowledged: Alert = { ...ALERTS[1], acknowledged: true, acknowledgedAt: null, acknowledgedBy: null };
    alertsService.list.mockReturnValue(of([legacyAcknowledged]));
    const fixture = createFixture();

    const card = fixture.debugElement.query(By.css('[data-testid="alert-a2"]'));
    expect(card.nativeElement.textContent).toContain('Atendida');
    expect(card.nativeElement.textContent).not.toContain('Atendida por');
  });

  it('shows the acknowledging dispatcher and formatted date/time for an already-acknowledged alert', () => {
    const fixture = createFixture();

    const card = fixture.debugElement.query(By.css('[data-testid="alert-a3"]'));
    expect(card.nativeElement.textContent).toContain('Atendida por dispatcher@acme.test');
    expect(card.nativeElement.textContent).toContain(formatLocal('2026-01-01T09:05:00.000Z'));
  });
});
