import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Card } from 'primeng/card';
import { Tag } from 'primeng/tag';
import type { VehicleView } from '../models/vehicle-view.model';

// Task 2.5 / DoD ("la interfaz nunca presenta el ETA como una hora exacta
// sin margen"): the ONLY place this library renders an ETA -- always as
// "~N min (+/- M min)", never a clock time (etaCalculatedAt is the moment
// this WAS computed, deliberately not surfaced as an arrival time either).
// Minutes, not seconds, matches this panel's own existing granularity
// (Speed is km/h, not m/s) and is what a dispatcher actually needs to
// decide with at a glance. Three states, not two: no destination assigned
// at all is different from a destination assigned but not yet recalculated
// by the live path (V11's own "NULL until the first live position"
// contract) -- collapsing them into one "Unknown" would hide a real,
// momentary "still calculating" state from the dispatcher.
export function formatEtaLabel(vehicle: VehicleView | undefined): string {
  if (!vehicle || vehicle.destinationLat === undefined || vehicle.destinationLon === undefined) {
    return 'No destination assigned';
  }
  if (vehicle.etaSeconds === undefined || vehicle.etaMarginSeconds === undefined) {
    return 'Calculating…';
  }
  const minutes = Math.round(vehicle.etaSeconds / 60);
  const marginMinutes = Math.round(vehicle.etaMarginSeconds / 60);
  // Prod QA fix: rounding to exactly 0 read as "~0 min", implying the
  // vehicle had already arrived -- "< 1 min" is honest about the estimate
  // still being a duration, just below this label's own minute granularity.
  const etaLabel = minutes === 0 ? '< 1 min' : `~${minutes} min`;
  const marginLabel = marginMinutes === 0 ? '< 1 min' : `${marginMinutes} min`;
  return `${etaLabel} (± ${marginLabel})`;
}

// Task 5.3: vehicle detail panel. Requirement "Separación entre posición
// interpolada y posición reportada" -- this component only ever renders
// whatever `VehicleView` it is given; it never has access to
// `VehicleInterpolationEngine`'s animated sample (that lives entirely inside
// `LiveMapComponent`). The container wires this to `FleetStore.vehicles()`
// (the real, un-interpolated map), never to the map's visual state, which is
// what makes the separation hold end-to-end, not just at the data layer
// WU4's own test already proved.
@Component({
  selector: 'console-ui-vehicle-detail',
  imports: [Card, Tag, DecimalPipe, DatePipe],
  templateUrl: './vehicle-detail.component.html',
  styleUrl: './vehicle-detail.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VehicleDetailComponent {
  readonly vehicle = input<VehicleView | undefined>(undefined);

  protected readonly etaLabel = computed(() => formatEtaLabel(this.vehicle()));
}
