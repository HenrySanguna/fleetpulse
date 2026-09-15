import { inject } from '@angular/core';
import { patchState, signalStore, withMethods, withState } from '@ngrx/signals';
import { type Observable, catchError, map, of, shareReplay } from 'rxjs';
import type { DispatcherSelfView } from '@fleetpulse/api-client';
import { AuthService } from './auth.service';

interface AuthStoreState {
  readonly dispatcher: DispatcherSelfView | null;
  readonly checked: boolean;
}

const INITIAL_STATE: AuthStoreState = {
  dispatcher: null,
  checked: false,
};

// Documented deviation from FleetStore/GeofenceStore's convention (pure
// state container, HTTP orchestration lives in a separate *Service):
// `ensureChecked()` here does call `AuthService.me()` directly. The guard
// and every route activation it protects need one shared cached in-flight
// call -- if orchestration lived in a separate AuthSessionService instead,
// that service would just need to read this store's `checked` flag AND hold
// its own in-flight-request cache in lockstep with it, which is the same
// coupling with an extra layer. Keeping both the cache and the state
// transition together here means there's exactly one place that can race.
export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState<AuthStoreState>(INITIAL_STATE),
  withMethods((store) => {
    const authService = inject(AuthService);
    let inFlight: Observable<boolean> | undefined;

    return {
      // Resolves to whether a dispatcher is currently signed in. Only ever
      // calls `me()` once per app load (or since the last `clear()`) --
      // concurrent callers (e.g. two guarded routes activating at once)
      // share the same in-flight Observable instead of firing two requests.
      ensureChecked(): Observable<boolean> {
        if (store.checked()) {
          return of(store.dispatcher() !== null);
        }
        if (!inFlight) {
          inFlight = authService.me().pipe(
            map((dispatcher) => {
              patchState(store, { dispatcher, checked: true });
              return true;
            }),
            catchError(() => {
              patchState(store, { dispatcher: null, checked: true });
              return of(false);
            }),
            shareReplay(1),
          );
        }
        return inFlight;
      },

      // Lets the login flow record the dispatcher it already has from a
      // successful login response, without a redundant `me()` round-trip.
      setDispatcher(dispatcher: DispatcherSelfView): void {
        inFlight = undefined;
        patchState(store, { dispatcher, checked: true });
      },

      clear(): void {
        inFlight = undefined;
        patchState(store, { dispatcher: null, checked: false });
      },
    };
  }),
);
