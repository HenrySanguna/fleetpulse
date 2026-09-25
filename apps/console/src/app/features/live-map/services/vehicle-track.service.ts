import { Injectable, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import type { TrackPointResponse } from '@fleetpulse/api-client';
import { VehicleTrackControllerService } from '@fleetpulse/api-client';
import { FleetStore } from './fleet.store';
import { toTrackSegmentFeatures, type TrackSegmentFeature } from './vehicle-track.util';

export type { TrackSegmentFeature };

// Prod QA (2026-09-24): the endpoint's `from`/`to` are optional (omitting
// them returns the *entire* history, per VehicleTrackController's own doc
// comment) -- unreadable on a long-lived vehicle. Bounding the request to a
// recent window is done here, once, rather than trusting every caller to
// remember it.
export const TRACK_WINDOW_HOURS = 2;

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
  // call in the app instead of a second, possibly-drifting copy. `from`/`to`
  // use the controller's own documented plain ISO-8601 instant format
  // (Instant.parse-compatible), computed fresh on every (re)selection rather
  // than once, so re-selecting the same vehicle later still asks for its
  // last `TRACK_WINDOW_HOURS`, not the window from the first selection.
  readonly track = httpResource<TrackPointResponse[]>(() => {
    const vehicleId = this.fleetStore.selectedVehicleId();
    if (!vehicleId) {
      return undefined;
    }
    const to = new Date();
    const from = new Date(to.getTime() - TRACK_WINDOW_HOURS * 60 * 60 * 1000);
    return {
      url: `${this.trackApi.configuration.basePath}/api/vehicles/${vehicleId}/track`,
      params: { from: from.toISOString(), to: to.toISOString() },
      withCredentials: this.trackApi.configuration.withCredentials,
    };
  });

  // Task 4.6 (extended, prod QA 2026-09-24): one feature per plausible run of
  // points (vehicle-track.util.ts's `splitTrackIntoSegments`), so an
  // implausible jump never draws as a straight line across it. Requirement
  // "Independencia del mapa en vivo respecto al servicio HTTP": this
  // resource failing (`track.error()`) never touches FleetStore or the live
  // vehicle layer -- only this line goes empty, the map itself keeps
  // updating from MQTT.
  readonly trackFeatures = computed<TrackSegmentFeature[]>(() => toTrackSegmentFeatures(this.track.value()));
}
