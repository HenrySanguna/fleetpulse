import { TestBed } from '@angular/core/testing';
import type { FleetFilterValue } from '../models/vehicle-view.model';
import { VehicleFiltersComponent } from './vehicle-filters.component';

const INITIAL: FleetFilterValue = { motionState: undefined, onlineOnly: false, search: '' };

describe('VehicleFiltersComponent', () => {
  function render(filters: FleetFilterValue = INITIAL) {
    const fixture = TestBed.createComponent(VehicleFiltersComponent);
    fixture.componentRef.setInput('filters', filters);
    fixture.detectChanges();
    return fixture;
  }

  // Task 5.1
  it('emits a search patch when the search box changes', async () => {
    const fixture = render();
    await fixture.whenStable();
    const emitted: Partial<FleetFilterValue>[] = [];
    fixture.componentInstance.filtersChange.subscribe((patch) => emitted.push(patch));

    const input = fixture.nativeElement.querySelector('input[pInputText]') as HTMLInputElement;
    input.value = 'truck';
    input.dispatchEvent(new Event('input'));

    expect(emitted).toEqual([{ search: 'truck' }]);
  });

  it('emits an onlineOnly patch when the checkbox toggles', async () => {
    const fixture = render();
    await fixture.whenStable();
    const emitted: Partial<FleetFilterValue>[] = [];
    fixture.componentInstance.filtersChange.subscribe((patch) => emitted.push(patch));

    // Asserted through the component's public output contract (its
    // protected `onOnlineOnlyChange` handler, wired to `p-checkbox`'s
    // `(ngModelChange)`) rather than the PrimeNG checkbox's internal DOM,
    // which is an implementation detail of a third-party component.
    fixture.componentInstance['onOnlineOnlyChange'](true);

    expect(emitted).toEqual([{ onlineOnly: true }]);
  });

  it('emits a motionState patch when the select value changes', async () => {
    const fixture = render();
    await fixture.whenStable();
    const emitted: Partial<FleetFilterValue>[] = [];
    fixture.componentInstance.filtersChange.subscribe((patch) => emitted.push(patch));

    fixture.componentInstance['onMotionStateChange']('IDLING');

    expect(emitted).toEqual([{ motionState: 'IDLING' }]);
  });
});
