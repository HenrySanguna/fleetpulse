import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    // Task 5.1: `app.html` now also renders `routerLink`s (the Live
    // map/Geofences nav) -- `RouterLink` injects the real `Router` service,
    // which (unlike `RouterOutlet`'s `ChildrenOutletContexts`) is not
    // `providedIn: 'root'`, so this spec needs an actual router config now.
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('renders the router outlet', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('router-outlet')).not.toBeNull();
  });

  it('renders navigation links to the live map and the geofencing editor', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('a[href="/"]')).not.toBeNull();
    expect(compiled.querySelector('a[href="/geofences"]')).not.toBeNull();
  });
});
