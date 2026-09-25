import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Tag } from 'primeng/tag';
import type { VehicleView } from '../models/vehicle-view.model';

// Task 5.1/5.2: the vehicle list. Presentational -- the container owns
// `FleetStore.visibleVehicles()`/`selectedVehicleId()` and passes them
// straight through; clicking a row only ever emits `vehicleSelected`, never
// calling `FleetStore.selectVehicle()` itself, so this component stays
// framework-state-free and independently testable.
@Component({
  selector: 'console-ui-vehicle-list',
  imports: [Tag],
  templateUrl: './vehicle-list.component.html',
  styleUrl: './vehicle-list.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VehicleListComponent {
  readonly vehicles = input.required<readonly VehicleView[]>();
  readonly selectedVehicleId = input<string | undefined>(undefined);
  readonly vehicleSelected = output<string>();

  protected motionSeverity(vehicle: VehicleView): 'success' | 'warn' | 'secondary' {
    switch (vehicle.motionState) {
      case 'MOVING':
        return 'success';
      case 'IDLING':
        return 'warn';
      default:
        return 'secondary';
    }
  }

  protected motionStateLabel(vehicle: VehicleView): string {
    switch (vehicle.motionState) {
      case 'MOVING':
        return 'En movimiento';
      case 'IDLING':
        return 'Ralentí';
      default:
        return 'Detenido';
    }
  }
}
