import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject } from '@angular/core';
import {
  ConnectionStatusComponent,
  VehicleDetailComponent,
  VehicleFiltersComponent,
  VehicleListComponent,
  type FleetFilterValue,
} from '@fleetpulse/console-ui';
import { MqttConnectionService } from '../../../core/mqtt/mqtt-connection.service';
import { LiveMapComponent } from '../components/live-map.component';
import { FleetStartupService } from '../services/fleet-startup.service';
import { FleetStore } from '../services/fleet.store';

// Tasks 5.1-5.4: the console page, composing `libs/console-ui`'s
// presentational widgets around WU1-WU4's already-built services/map. All
// state wiring lives here (container), never in `libs/console-ui`
// (presentational) -- the widgets only ever see plain inputs and emit plain
// outputs. `FleetStore.selectedVehicleId`/`selectVehicle()` (WU4) is the one
// shared selection source of truth the list, the detail panel and
// `LiveMapComponent`'s marker click-to-select all read/write.
@Component({
  selector: 'app-live-map-page',
  imports: [
    ConnectionStatusComponent,
    VehicleDetailComponent,
    VehicleFiltersComponent,
    VehicleListComponent,
    LiveMapComponent,
  ],
  templateUrl: './live-map-page.component.html',
  styleUrl: './live-map-page.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveMapPageComponent implements OnDestroy {
  private readonly fleetStore = inject(FleetStore);
  private readonly mqttConnectionService = inject(MqttConnectionService);
  private readonly fleetStartupService = inject(FleetStartupService);

  protected readonly vehicles = this.fleetStore.visibleVehicles;
  protected readonly filters = this.fleetStore.filters;
  protected readonly selectedVehicleId = this.fleetStore.selectedVehicleId;
  protected readonly connectionStatus = this.mqttConnectionService.status;

  // Task 5.3: reads FleetStore's real vehicle map directly, never
  // `LiveMapComponent`'s interpolated visual state (it has no access to it
  // at all) and never the *filtered* `visibleVehicles()` either -- a
  // selected vehicle's detail must stay visible even if it drops out of the
  // active search/filters.
  protected readonly selectedVehicle = computed(() => {
    const vehicleId = this.selectedVehicleId();
    return vehicleId ? this.fleetStore.vehicles().get(vehicleId) : undefined;
  });

  constructor() {
    // Nothing triggered design.md's snapshot+stream startup sequence (WU3)
    // before this page existed -- this is its first real caller.
    this.fleetStartupService.start();
  }

  ngOnDestroy(): void {
    this.fleetStartupService.stop();
  }

  protected onFiltersChange(patch: Partial<FleetFilterValue>): void {
    this.fleetStore.setFilters(patch);
  }

  // Task 5.2: list -> map. `FleetStore.selectVehicle()` is the same method
  // `LiveMapComponent`'s marker click handler calls (WU4), so both
  // directions converge on one signal.
  protected onVehicleSelected(vehicleId: string): void {
    this.fleetStore.selectVehicle(vehicleId);
  }
}
