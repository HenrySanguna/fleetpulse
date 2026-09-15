import { Component, input, output } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import { of, throwError } from 'rxjs';
import type { GeofenceResponse } from '@fleetpulse/api-client';
import type { GeofenceDraft } from '../models/geofence-draft.model';
import { GeofenceService } from '../services/geofence.service';
import { GeofenceStore } from '../services/geofence.store';
import { GeofenceEditorPageComponent } from './geofence-editor-page.component';

// Stands in for the real drawing editor (its own MapLibre internals are
// covered by its own spec) -- same "compose with a fake child, never
// re-mock the third-party map library here" split
// live-map-page.component.spec.ts already established for LiveMapComponent.
@Component({ selector: 'app-geofence-drawing-editor', template: '' })
class FakeGeofenceDrawingEditorComponent {
  readonly existingGeofences = input<readonly GeofenceResponse[]>([]);
  readonly resetToken = input<number>(0);
  readonly draftChange = output<GeofenceDraft | undefined>();
}

type Fixture = ComponentFixture<GeofenceEditorPageComponent>;

function setNameInput(fixture: Fixture, value: string): void {
  const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
  nameInput.value = value;
  nameInput.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

function emitDraft(fixture: Fixture, draft: GeofenceDraft | undefined): void {
  const editor = fixture.debugElement.query(By.directive(FakeGeofenceDrawingEditorComponent))
    .componentInstance as FakeGeofenceDrawingEditorComponent;
  editor.draftChange.emit(draft);
  fixture.detectChanges();
}

function click(fixture: Fixture, testId: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${testId}"]`))?.triggerEventHandler('click', undefined);
  fixture.detectChanges();
}

function clickButton(fixture: Fixture, testId: string): void {
  fixture.debugElement.query(By.css(`[data-testid="${testId}"]`))?.triggerEventHandler('onClick', undefined);
  fixture.detectChanges();
}

function saveButtonDisabled(fixture: Fixture): boolean {
  return (fixture.debugElement.query(By.css('[data-testid="save-geofence"]')).componentInstance as { disabled: boolean }).disabled;
}

describe('GeofenceEditorPageComponent', () => {
  let geofenceService: { load: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn> };
  let store: InstanceType<typeof GeofenceStore>;

  beforeEach(() => {
    geofenceService = { load: vi.fn(), create: vi.fn(), update: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: GeofenceService, useValue: geofenceService }],
    });
    TestBed.overrideComponent(GeofenceEditorPageComponent, {
      set: { imports: [ReactiveFormsModule, Button, InputNumber, InputText, Select, FakeGeofenceDrawingEditorComponent] },
    });
    store = TestBed.inject(GeofenceStore);
  });

  function createFixture() {
    const fixture = TestBed.createComponent(GeofenceEditorPageComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('loads the geofence list on init', () => {
    createFixture();
    expect(geofenceService.load).toHaveBeenCalledTimes(1);
  });

  // Task 5.2 (console half): a new geofence needs both a valid name and a
  // drawn shape before Save is enabled.
  it('keeps Save disabled for a new geofence until a name is entered AND a shape is drawn', () => {
    const fixture = createFixture();
    expect(saveButtonDisabled(fixture)).toBe(true);

    setNameInput(fixture, 'Depot');
    expect(saveButtonDisabled(fixture)).toBe(true);

    emitDraft(fixture, { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] });
    expect(saveButtonDisabled(fixture)).toBe(false);
  });

  it('save() for a new polygon draft calls GeofenceService.create with the drawn geometry and resets the form', () => {
    geofenceService.create.mockReturnValue(of({ id: 'g1', name: 'Depot' }));
    const fixture = createFixture();
    setNameInput(fixture, 'Depot');
    emitDraft(fixture, { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] });

    clickButton(fixture, 'save-geofence');

    expect(geofenceService.create).toHaveBeenCalledWith({
      name: 'Depot',
      rule: 'ON_ENTER',
      dwellSecs: undefined,
      shape: 'POLYGON',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    });
    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
    expect(nameInput.value).toBe('');
  });

  it('save() for a new circle draft calls GeofenceService.create with center/radiusMeters, never vertices', () => {
    geofenceService.create.mockReturnValue(of({ id: 'g1', name: 'Yard' }));
    const fixture = createFixture();
    setNameInput(fixture, 'Yard');
    emitDraft(fixture, { shape: 'CIRCLE', center: { lat: 5, lon: 5 }, radiusMeters: 120 });

    clickButton(fixture, 'save-geofence');

    expect(geofenceService.create).toHaveBeenCalledWith({
      name: 'Yard',
      rule: 'ON_ENTER',
      dwellSecs: undefined,
      shape: 'CIRCLE',
      center: { lat: 5, lon: 5 },
      radiusMeters: 120,
    });
  });

  // Task 5.2's documented edit deviation: the form is pre-filled, and the
  // dwellSecs field becomes visible immediately, from the selected
  // geofence's own data -- no drawing-editor interaction involved.
  it('selecting an existing on_dwell geofence pre-fills the form and shows the dwellSecs field', () => {
    const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_DWELL', dwellSecs: 60, vertices: [] };
    store.setGeofences([existing]);
    const fixture = createFixture();

    click(fixture, 'select-geofence-g1');

    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
    expect(nameInput.value).toBe('Depot');
    expect(fixture.nativeElement.querySelector('[data-testid="geofence-dwell-input"]')).not.toBeNull();
    expect(saveButtonDisabled(fixture)).toBe(false);
  });

  it('save() for an existing geofence resends its original vertices reclassified as POLYGON, without requiring a draft', () => {
    const existing: GeofenceResponse = {
      id: 'g1',
      name: 'Depot',
      rule: 'ON_ENTER',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }, { lat: 1, lon: 1 }],
    };
    store.setGeofences([existing]);
    geofenceService.update.mockReturnValue(of({ ...existing, name: 'Depot (renamed)' }));
    const fixture = createFixture();
    click(fixture, 'select-geofence-g1');

    setNameInput(fixture, 'Depot (renamed)');
    clickButton(fixture, 'save-geofence');

    expect(geofenceService.update).toHaveBeenCalledWith('g1', {
      name: 'Depot (renamed)',
      rule: 'ON_ENTER',
      dwellSecs: undefined,
      shape: 'POLYGON',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    });
  });

  it('"New" resets the form/selection and bumps the drawing editor resetToken', () => {
    const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_ENTER', vertices: [] };
    store.setGeofences([existing]);
    const fixture = createFixture();
    click(fixture, 'select-geofence-g1');

    const editorBefore = fixture.debugElement.query(By.directive(FakeGeofenceDrawingEditorComponent))
      .componentInstance as FakeGeofenceDrawingEditorComponent;
    const tokenBefore = editorBefore.resetToken();

    clickButton(fixture, 'new-geofence');

    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
    expect(nameInput.value).toBe('');
    expect(editorBefore.resetToken()).toBeGreaterThan(tokenBefore);
  });

  it('save() surfaces an error and keeps the entered name when the API call fails', () => {
    geofenceService.create.mockReturnValue(throwError(() => new Error('boom')));
    const fixture = createFixture();
    setNameInput(fixture, 'Depot');
    emitDraft(fixture, { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] });

    clickButton(fixture, 'save-geofence');

    expect(fixture.nativeElement.textContent).toContain('Failed to save the geofence');
    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
    expect(nameInput.value).toBe('Depot');
  });
});
