import { TestBed } from '@angular/core/testing';
import type { VehicleView } from '../models/vehicle-view.model';
import { VehicleListComponent } from './vehicle-list.component';

const VEHICLES: VehicleView[] = [
  { vehicleId: 'v1', label: 'Truck 1', motionState: 'MOVING', online: true },
  { vehicleId: 'v2', label: 'Van 2', motionState: 'STOPPED', online: false },
];

describe('VehicleListComponent', () => {
  function render(vehicles: readonly VehicleView[], selectedVehicleId?: string) {
    const fixture = TestBed.createComponent(VehicleListComponent);
    fixture.componentRef.setInput('vehicles', vehicles);
    fixture.componentRef.setInput('selectedVehicleId', selectedVehicleId);
    fixture.detectChanges();
    return fixture;
  }

  // Task 5.1
  it('renders one row per vehicle with its label and vehicle id', async () => {
    const fixture = render(VEHICLES);
    await fixture.whenStable();

    const rows = fixture.nativeElement.querySelectorAll('li');
    expect(rows.length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Truck 1');
    expect(fixture.nativeElement.textContent).toContain('Van 2');
  });

  it('shows an empty-state message when there are no vehicles to show', async () => {
    const fixture = render([]);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Ningún vehículo coincide con los filtros actuales.');
  });

  // Task 5.2: list -> selection. The row only emits; it never calls
  // FleetStore itself (container owns that).
  it('emits vehicleSelected with the clicked vehicle id', async () => {
    const fixture = render(VEHICLES);
    await fixture.whenStable();
    const emitted: string[] = [];
    fixture.componentInstance.vehicleSelected.subscribe((id) => emitted.push(id));

    const buttons = fixture.nativeElement.querySelectorAll('button');
    (buttons[1] as HTMLButtonElement).click();

    expect(emitted).toEqual(['v2']);
  });

  // Task 5.2: map -> selection reflected in the list.
  it('marks the row matching selectedVehicleId as selected', async () => {
    const fixture = render(VEHICLES, 'v2');
    await fixture.whenStable();

    const buttons = fixture.nativeElement.querySelectorAll('button');
    expect(buttons[0].getAttribute('aria-selected')).toBe('false');
    expect(buttons[1].getAttribute('aria-selected')).toBe('true');
  });
});
