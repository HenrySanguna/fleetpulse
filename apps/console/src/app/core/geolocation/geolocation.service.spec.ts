import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { GeolocationService } from './geolocation.service';

describe('GeolocationService', () => {
  let getCurrentPosition: ReturnType<typeof vi.fn>;
  let originalGeolocation: Geolocation | undefined;

  beforeEach(() => {
    originalGeolocation = navigator.geolocation;
    getCurrentPosition = vi.fn();
    Object.defineProperty(navigator, 'geolocation', {
      value: { getCurrentPosition },
      configurable: true,
    });
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    Object.defineProperty(navigator, 'geolocation', { value: originalGeolocation, configurable: true });
    vi.useRealTimers();
  });

  it('resolves the browser-reported coordinates when permission is granted', async () => {
    getCurrentPosition.mockImplementation((success: (position: GeolocationPosition) => void) => {
      success({ coords: { latitude: 12, longitude: 34 } } as GeolocationPosition);
    });
    const service = TestBed.inject(GeolocationService);

    await expect(firstValueFrom(service.position())).resolves.toEqual({ lat: 12, lon: 34 });
  });

  it('resolves to undefined when permission is denied', async () => {
    getCurrentPosition.mockImplementation((_success: unknown, error: (err: GeolocationPositionError) => void) => {
      error({ code: 1, message: 'denied' } as GeolocationPositionError);
    });
    const service = TestBed.inject(GeolocationService);

    await expect(firstValueFrom(service.position())).resolves.toBeUndefined();
  });

  it('resolves to undefined when navigator.geolocation is unavailable (SSR/older browser)', async () => {
    Object.defineProperty(navigator, 'geolocation', { value: undefined, configurable: true });
    const service = TestBed.inject(GeolocationService);

    await expect(firstValueFrom(service.position())).resolves.toBeUndefined();
    expect(getCurrentPosition).not.toHaveBeenCalled();
  });

  it('resolves to undefined if the browser never calls back within the timeout', async () => {
    vi.useFakeTimers();
    getCurrentPosition.mockImplementation(() => {
      /* never resolves -- simulates a stalled permission prompt */
    });
    const service = TestBed.inject(GeolocationService);

    const resultPromise = firstValueFrom(service.position());
    await vi.advanceTimersByTimeAsync(9000);

    await expect(resultPromise).resolves.toBeUndefined();
  });

  it('caches the resolved position for the session so a second caller never re-prompts', async () => {
    getCurrentPosition.mockImplementation((success: (position: GeolocationPosition) => void) => {
      success({ coords: { latitude: 1, longitude: 2 } } as GeolocationPosition);
    });
    const service = TestBed.inject(GeolocationService);

    await firstValueFrom(service.position());
    await firstValueFrom(service.position());

    expect(getCurrentPosition).toHaveBeenCalledTimes(1);
  });
});
