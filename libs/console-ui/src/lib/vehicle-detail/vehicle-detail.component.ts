import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Card } from 'primeng/card';
import { Tag } from 'primeng/tag';
import type { VehicleView } from '../models/vehicle-view.model';

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
  imports: [Card, Tag],
  templateUrl: './vehicle-detail.component.html',
  styleUrl: './vehicle-detail.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VehicleDetailComponent {
  readonly vehicle = input<VehicleView | undefined>(undefined);
}
