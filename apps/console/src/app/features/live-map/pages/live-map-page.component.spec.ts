import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import {
  ConnectionStatusComponent,
  VehicleDetailComponent,
  VehicleFiltersComponent,
  VehicleListComponent,
} from '@fleetpulse/console-ui';
import { FleetStartupService } from '../services/fleet-startup.service';
import { FleetStore } from '../services/fleet.store';
import { LiveMapPageComponent } from './live-map-page.component';

// This spec is about composition/wiring only -- WU4's own spec already
// covers `LiveMapComponent`'s MapLibre internals, and WU3's own spec already
// covers `FleetStartupService`'s snapshot+buffer+stream cycle. Both are
// stubbed here so this file never has to re-mock `maplibre-gl` or HTTP.
@Component({ selector: 'app-live-map', template: '' })
class FakeLiveMapComponent {}

describe('LiveMapPageComponent', () => {
  let store: InstanceType<typeof FleetStore>;
  let startupService: { start: ReturnType<typeof vi.fn>; stop: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    startupService = { start: vi.fn(), stop: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: FleetStartupService, useValue: startupService }],
    });
    TestBed.overrideComponent(LiveMapPageComponent, {
      set: {
        imports: [
          ConnectionStatusComponent,
          VehicleDetailComponent,
          VehicleFiltersComponent,
          VehicleListComponent,
          FakeLiveMapComponent,
        ],
      },
    });
    store = TestBed.inject(FleetStore);
  });

  // Task 5.1/5.4: starts the WU3 startup sequence and reflects WU1's
  // connection status as soon as the page exists.
  it('starts FleetStartupService on init and stops it on destroy', async () => {
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();
    expect(startupService.start).toHaveBeenCalledTimes(1);

    fixture.destroy();
    expect(startupService.stop).toHaveBeenCalledTimes(1);
  });

  it('forwards the live MQTT connection status to console-ui-connection-status', async () => {
    // FleetStartupService is stubbed above, so MqttConnectionService.connect()
    // is never called -- status stays at its initial 'disconnected'.
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();

    const status = fixture.nativeElement.querySelector('console-ui-connection-status');
    expect(status.textContent).toContain('Disconnected');
  });

  // Task 5.2: list -> store, and the store is the same signal LiveMapComponent
  // (WU4) reads/writes for its own marker click-to-select.
  it('selecting a vehicle in the list updates FleetStore.selectedVehicleId', async () => {
    store.applySnapshot({ vehicles: [{ vehicleId: 'v1', lat: 1, lon: 2, recordedAt: '2026-01-01T00:00:00Z' }] });
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();

    const list = fixture.debugElement.query(By.directive(VehicleListComponent))
      .componentInstance as InstanceType<typeof VehicleListComponent>;
    list.vehicleSelected.emit('v1');

    expect(store.selectedVehicleId()).toBe('v1');
  });

  // Task 5.1
  it('forwards a filters patch from console-ui-vehicle-filters to FleetStore.setFilters', async () => {
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();

    const filtersComponent = fixture.debugElement.query(By.directive(VehicleFiltersComponent))
      .componentInstance as InstanceType<typeof VehicleFiltersComponent>;
    filtersComponent.filtersChange.emit({ onlineOnly: true });

    expect(store.filters().onlineOnly).toBe(true);
  });

  // Task 5.3 / Test 6.3 (end-to-end): the detail panel is wired to
  // `FleetStore.vehicles()` (real state), never to `LiveMapComponent`'s
  // interpolated visual state, which this container never even reads.
  it('shows the exact reported state for the selected vehicle, regardless of visibleVehicles filtering', async () => {
    store.applySnapshot({
      vehicles: [
        { vehicleId: 'v1', label: 'Truck 1', lat: 5, lon: 6, recordedAt: '2026-01-01T00:00:00Z', online: false },
      ],
    });
    store.setFilters({ onlineOnly: true }); // v1 is offline: filtered out of visibleVehicles()
    store.selectVehicle('v1');
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();

    expect(store.visibleVehicles().some((v) => v.vehicleId === 'v1')).toBe(false);
    const detail = fixture.nativeElement.querySelector('[data-testid="vehicle-detail-position"]');
    expect(detail.textContent).toContain('5.00000, 6.00000');
  });

  // Prod QA (2026-09-24): the track needed a legend entry of its own so its
  // distinct color reads as "historical track", not an unlabeled line.
  it('shows a legend entry for the vehicle track', async () => {
    const fixture = TestBed.createComponent(LiveMapPageComponent);
    await fixture.whenStable();

    const legend = fixture.nativeElement.querySelector('.map-legend');
    expect(legend.textContent).toContain('Recorrido');
  });
});
