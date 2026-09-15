import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import type { Alert } from '../models/alert.model';
import { AlertsService } from '../services/alerts.service';
import { AlertsPageComponent } from './alerts-page.component';

const NOW = Date.now();
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
  },
  {
    id: 'a2',
    vehicleId: 'VH-0892',
    vehicleLabel: 'Furgoneta 02',
    type: 'speeding',
    detail: '92 km/h en una zona con límite de 60 km/h',
    occurredAt: new Date(NOW - 60_000).toISOString(),
    acknowledged: false,
  },
  {
    id: 'a3',
    vehicleId: 'VH-1107',
    vehicleLabel: 'Camión 11',
    type: 'excessive_idle',
    detail: '22 min detenido con el motor en marcha',
    occurredAt: new Date(NOW - 120_000).toISOString(),
    acknowledged: true,
  },
];

describe('AlertsPageComponent', () => {
  let alertsService: { list: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    alertsService = { list: vi.fn(() => of(ALERTS)) };
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
    expect(sub.textContent).toContain('2 sin reconocer de 3');
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
});
