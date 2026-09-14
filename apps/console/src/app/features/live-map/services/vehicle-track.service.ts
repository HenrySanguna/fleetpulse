import { Injectable, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { Feature, LineString } from 'geojson';
import type { TrackPointResponse } from '@fleetpulse/api-client';
import { VehicleTrackControllerService } from '@fleetpulse/api-client';
import { FleetStore } from './fleet.store';

export type TrackLineFeature = Feature<LineString, Record<string, never>>;

// Task 3.3: the selected vehicle's historical track is a genuine HTTP
// request with a reactive dependency (which vehicle is selected) -- exactly
// what `httpResource` is for, unlike the pushed MQTT stream FleetStore owns
// (design.md's own "httpResource() for HTTP, SignalStore for the stream"
// distinction). Re-selecting a vehicle automatically refetches; there is
// nothing to manage manually.
@Injectable({ providedIn: 'root' })
export class VehicleTrackService {
  private readonly trackApi = inject(VehicleTrackControllerService);
  private readonly fleetStore = inject(FleetStore);

  // Injecting the generated `VehicleTrackControllerService` (rather than
  // hand-building the request) rather than duplicating a base URL: its
  // `BaseService` constructor pins `Configuration.basePath` (and
  // `withCredentials`, the CSRF-cookie flag `app.config.ts` sets via
  // `provideApi`) the moment it is constructed, so reading them back here
  // keeps this resource's request identical to every other libs/api-client
  // call in the app instead of a second, possibly-drifting copy.
  readonly track = httpResource<TrackPointResponse[]>(() => {
    const vehicleId = this.fleetStore.selectedVehicleId();
    if (!vehicleId) {
      return undefined;
    }
    return {
      url: `${this.trackApi.configuration.basePath}/api/vehicles/${vehicleId}/track`,
      withCredentials: this.trackApi.configuration.withCredentials,
    };
  });

  // Task 4.6: the line feature LiveMapComponent draws directly via
  // `source.setData(...)`. Requirement "Independencia del mapa en vivo
  // respecto al servicio HTTP": this resource failing (`track.error()`)
  // never touches FleetStore or the live vehicle layer -- only this line
  // goes empty, the map itself keeps updating from MQTT.
  readonly trackLine = computed<TrackLineFeature | undefined>(() => toTrackLineFeature(this.track.value()));
}

// Task 4.6: the backend already simplifies the track (Geo.simplifyTrack,
// WU2) before serving it, so no further simplification happens here -- only
// the GeoJSON shape conversion, dropping any point missing a coordinate.
export function toTrackLineFeature(points: TrackPointResponse[] | undefined): TrackLineFeature | undefined {
  if (!points) {
    return undefined;
  }
  const coordinates: [number, number][] = [];
  for (const point of points) {
    if (typeof point.lat === 'number' && typeof point.lon === 'number') {
      coordinates.push([point.lon, point.lat]);
    }
  }
  if (coordinates.length < 2) {
    return undefined;
  }
  return { type: 'Feature', geometry: { type: 'LineString', coordinates }, properties: {} };
}
