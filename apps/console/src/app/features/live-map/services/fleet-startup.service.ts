import { Injectable, inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { Observable, Subscription, filter, switchMap } from 'rxjs';
import { DispatcherSessionControllerService, FleetStateControllerService } from '@fleetpulse/api-client';
import { MqttConnectionService } from '../../../core/mqtt/mqtt-connection.service';
import type { MqttInboundMessage } from '../../../core/mqtt/mqtt-connection.models';
import { FleetStore } from './fleet.store';
import { mapInboundMessage } from './fleet-message.mapper';

// Tasks 2.2-2.4: orchestrates design.md's "snapshot + stream" startup
// sequence:
//   1. connect + SUBSCRIBE   MqttConnectionService already does both before
//                             it reports 'connected' (task 1.4).
//   2. buffer                messages$ is collected instead of applied.
//   3. snapshot               GET /api/fleet/state.
//   4. apply snapshot         replaces the store wholesale.
//   5. apply the buffer       through the same monotonicity-guarded
//                             FleetStore.applyUpdate() the live path uses.
//   6. apply live directly    from here on, every message is forwarded as
//                             it arrives.
//
// Task 2.4 ("repeat the full cycle on reconnect") falls out of this for
// free: MqttConnectionService.status() transitions to 'connected' again on
// reconnect exactly the same way it does on first connect, so a single
// `filter(status === 'connected')` stream covers both cases without having
// to special-case "is this a reconnect". `switchMap` tears down the
// previous cycle's subscriptions -- including its "apply live directly"
// forwarding -- the instant a new 'connected' status arrives, which is
// exactly the desired "stop trusting the old state, start over" behavior.
@Injectable({ providedIn: 'root' })
export class FleetStartupService {
  private readonly mqttConnectionService = inject(MqttConnectionService);
  private readonly dispatcherSessionApi = inject(DispatcherSessionControllerService);
  private readonly fleetStateApi = inject(FleetStateControllerService);
  private readonly fleetStore = inject(FleetStore);

  // Built eagerly (constructor-time injection context) so `start()` itself
  // never needs to call `toObservable()`, which requires an injection
  // context and would otherwise force every caller to pass one in.
  private readonly connectedStatus$ = toObservable(this.mqttConnectionService.status).pipe(
    filter((status) => status === 'connected'),
  );

  private cycleSubscription: Subscription | undefined;
  private started = false;

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;

    this.cycleSubscription = this.connectedStatus$.pipe(switchMap(() => this.runStartupCycle())).subscribe();

    this.dispatcherSessionApi.me().subscribe({
      next: (dispatcher) => {
        if (dispatcher.organizationId) {
          this.mqttConnectionService.connect(dispatcher.organizationId);
        }
      },
      error: (error: unknown) => {
        console.error('FleetStartupService: failed to resolve the current dispatcher', error);
      },
    });
  }

  stop(): void {
    this.cycleSubscription?.unsubscribe();
    this.cycleSubscription = undefined;
    this.started = false;
    this.mqttConnectionService.disconnect();
  }

  // Never completes and never errors on its own: it keeps forwarding live
  // messages to the store indefinitely once the snapshot has been applied.
  // It only ever ends when `switchMap` tears it down for a fresh cycle
  // (reconnect) or `stop()` unsubscribes the outer subscription. A snapshot
  // fetch failure is logged, not surfaced as an observable error: erroring
  // here would propagate through `switchMap` and permanently kill the outer
  // `connectedStatus$` subscription, silently disabling every future
  // reconnect cycle for the rest of the app's lifetime. The known tradeoff
  // (documented in tasks.md) is that this cycle does not itself retry a
  // failed snapshot fetch -- recovery relies on the next actual MQTT
  // reconnect, which starts a brand new cycle.
  private runStartupCycle(): Observable<never> {
    return new Observable<never>(() => {
      const buffer: MqttInboundMessage[] = [];
      let snapshotApplied = false;

      const messagesSubscription = this.mqttConnectionService.messages$.subscribe((message) => {
        if (snapshotApplied) {
          this.applyMessage(message);
        } else {
          buffer.push(message);
        }
      });

      const snapshotSubscription = this.fleetStateApi.state().subscribe({
        next: (snapshot) => {
          this.fleetStore.applySnapshot(snapshot);
          for (const buffered of buffer) {
            this.applyMessage(buffered);
          }
          buffer.length = 0;
          snapshotApplied = true;
        },
        error: (error: unknown) => {
          console.error('FleetStartupService: failed to load the fleet state snapshot', error);
        },
      });

      return () => {
        messagesSubscription.unsubscribe();
        snapshotSubscription.unsubscribe();
      };
    });
  }

  private applyMessage(message: MqttInboundMessage): void {
    const update = mapInboundMessage(message);
    if (update) {
      this.fleetStore.applyUpdate(update);
    }
  }
}
