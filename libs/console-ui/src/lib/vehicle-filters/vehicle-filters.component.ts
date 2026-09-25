import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Checkbox } from 'primeng/checkbox';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import type { FleetFilterValue, VehicleMotionState } from '../models/vehicle-view.model';

interface MotionStateOption {
  readonly label: string;
  readonly value: VehicleMotionState | undefined;
}

const MOTION_STATE_OPTIONS: MotionStateOption[] = [
  { label: 'Todos los estados', value: undefined },
  { label: 'En movimiento', value: 'MOVING' },
  { label: 'Ralentí', value: 'IDLING' },
  { label: 'Detenido', value: 'STOPPED' },
];

// Task 5.1: filter/search controls for the side panel. Presentational --
// receives the current `FleetFilterValue` and only ever emits a partial
// patch, matching `FleetStore.setFilters()`'s own merge semantics (task 3.2)
// so the container can forward `filtersChange` events straight to the store
// with no translation.
//
// `[(ngModel)]` (FormsModule) is used here purely as a value binder for two
// standalone PrimeNG controls (`p-select`, `p-checkbox`, both
// ControlValueAccessors) -- there is no `<form>`, no validation and nothing
// submitted, so this is not the template-driven-forms pattern the project's
// conventions steer away from for actual data-entry forms.
@Component({
  selector: 'console-ui-vehicle-filters',
  imports: [FormsModule, Checkbox, InputText, Select],
  templateUrl: './vehicle-filters.component.html',
  styleUrl: './vehicle-filters.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VehicleFiltersComponent {
  readonly filters = input.required<FleetFilterValue>();
  readonly filtersChange = output<Partial<FleetFilterValue>>();

  protected readonly motionStateOptions: MotionStateOption[] = MOTION_STATE_OPTIONS;

  protected onSearchInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.filtersChange.emit({ search: value });
  }

  protected onMotionStateChange(motionState: VehicleMotionState | undefined): void {
    this.filtersChange.emit({ motionState });
  }

  protected onOnlineOnlyChange(onlineOnly: boolean): void {
    this.filtersChange.emit({ onlineOnly });
  }
}
