import { TestBed } from '@angular/core/testing';
import type { VehicleView } from '../models/vehicle-view.model';
import { VehicleDetailComponent, formatEtaLabel } from './vehicle-detail.component';

describe('VehicleDetailComponent', () => {
  function render(vehicle: VehicleView | undefined) {
    const fixture = TestBed.createComponent(VehicleDetailComponent);
    fixture.componentRef.setInput('vehicle', vehicle);
    fixture.detectChanges();
    return fixture;
  }

  it('shows a placeholder when no vehicle is selected', async () => {
    const fixture = render(undefined);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Select a vehicle to see its details.');
    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail"]')).toBeNull();
  });

  // Task 5.3 / Test 6.3 (component-level): the detail panel renders exactly
  // the reported position it was given -- this component has no access to
  // `VehicleInterpolationEngine` at all (it lives only inside
  // `LiveMapComponent`), so there is no interpolated value it *could* render
  // even mid-animation. The container wires this input from
  // `FleetStore.vehicles()` (real state), never from the map.
  it('renders the exact reported position, motion state and timestamp it is given', async () => {
    const vehicle: VehicleView = {
      vehicleId: 'v1',
      label: 'Truck 1',
      lat: 10.123456,
      lon: -20.654321,
      recordedAt: '2026-01-01T00:00:05Z',
      speedKmh: 42,
      heading: 90,
      motionState: 'MOVING',
      online: true,
    };
    const fixture = render(vehicle);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-position"]').textContent).toContain(
      '10.123456, -20.654321',
    );
    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-motion-state"]').textContent).toContain(
      'MOVING',
    );
    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-recorded-at"]').textContent).toContain(
      '2026-01-01T00:00:05Z',
    );
  });

  it('renders "Unknown" placeholders for a vehicle with no telemetry yet', async () => {
    const fixture = render({ vehicleId: 'v2', online: false });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-position"]').textContent).toContain(
      'Unknown',
    );
  });

  // Task 2.5 / DoD: the ETA is NEVER rendered as a bare, exact duration --
  // always with its own margin attached in the same string.
  it('renders the eta as an approximate duration with its margin, never an exact time', async () => {
    const vehicle: VehicleView = {
      vehicleId: 'v3',
      destinationLat: 4.8,
      destinationLon: -74.1,
      etaSeconds: 900,
      etaMarginSeconds: 240,
    };
    const fixture = render(vehicle);
    await fixture.whenStable();

    const etaText = fixture.nativeElement.querySelector('[data-testid="vehicle-detail-eta"]').textContent;
    expect(etaText).toContain('~15 min');
    expect(etaText).toContain('4 min');
    expect(etaText).not.toMatch(/\d{1,2}:\d{2}/);
  });

  it('renders "Calculating" for an assigned destination whose eta has not been computed yet', async () => {
    const fixture = render({ vehicleId: 'v4', destinationLat: 4.8, destinationLon: -74.1 });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-eta"]').textContent).toContain(
      'Calculating',
    );
  });

  it('renders "No destination assigned" when the vehicle has no destination', async () => {
    const fixture = render({ vehicleId: 'v5' });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('[data-testid="vehicle-detail-eta"]').textContent).toContain(
      'No destination assigned',
    );
  });
});

describe('formatEtaLabel', () => {
  it('rounds seconds to minutes for both the estimate and its margin', () => {
    expect(formatEtaLabel({ vehicleId: 'v1', destinationLat: 1, destinationLon: 1, etaSeconds: 905, etaMarginSeconds: 269 })).toBe(
      '~15 min (± 4 min)',
    );
  });

  it('returns undefined-destination placeholder for an undefined vehicle', () => {
    expect(formatEtaLabel(undefined)).toBe('No destination assigned');
  });
});
