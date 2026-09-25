import { Component, input, output } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import { of, throwError } from 'rxjs';
import type { DispatcherSelfView, GeofenceResponse } from '@fleetpulse/api-client';
import { AuthStore } from '../../../core/auth/auth.store';
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
  readonly readOnly = input<boolean>(false);
  readonly selectedGeofence = input<GeofenceResponse | undefined>(undefined);
  readonly draftChange = output<GeofenceDraft | undefined>();
}

const FLEET_ADMIN: DispatcherSelfView = { id: 'admin1', organizationId: 'org-1', email: 'admin@example.com', role: 'FLEET_ADMIN' };
const DISPATCHER: DispatcherSelfView = { id: 'd1', organizationId: 'org-1', email: 'dispatcher@example.com', role: 'DISPATCHER' };

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

// p-select/p-inputNumber implement ControlValueAccessor themselves (unlike
// pInputText, a directive on a native input whose `disabled` DOM property
// Angular's own DefaultValueAccessor sets directly) -- their effective
// disabled state is their own `$disabled` computed, set by setDisabledState.
function fieldDisabled(fixture: Fixture, testId: string): boolean {
  return (fixture.debugElement.query(By.css(`[data-testid="${testId}"]`)).componentInstance as { $disabled: () => boolean }).$disabled();
}

describe('GeofenceEditorPageComponent', () => {
  let geofenceService: { load: ReturnType<typeof vi.fn>; create: ReturnType<typeof vi.fn>; update: ReturnType<typeof vi.fn> };
  let store: InstanceType<typeof GeofenceStore>;
  let authStore: InstanceType<typeof AuthStore>;

  beforeEach(() => {
    geofenceService = { load: vi.fn(), create: vi.fn(), update: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: GeofenceService, useValue: geofenceService }],
    });
    TestBed.overrideComponent(GeofenceEditorPageComponent, {
      set: { imports: [ReactiveFormsModule, Button, InputNumber, InputText, Select, FakeGeofenceDrawingEditorComponent] },
    });
    store = TestBed.inject(GeofenceStore);
    authStore = TestBed.inject(AuthStore);
    // Every existing test below exercises FLEET_ADMIN behaviour (the console's
    // historical default before dispatcher UX existed); the dedicated 'role-based
    // access' describe block below overrides this per test for the DISPATCHER case.
    authStore.setDispatcher(FLEET_ADMIN);
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

  // Regression: canSave used to read the form's plain (non-signal) `.valid`
  // getter inside computed(), so it only re-evaluated when `draft()` changed
  // -- drawing the shape before typing the name left Save stuck disabled
  // even after a valid name was entered, because no signal read observed
  // the form becoming valid.
  it('keeps Save disabled for a new geofence until a name is entered AND a shape is drawn, when the shape is drawn first', () => {
    const fixture = createFixture();
    expect(saveButtonDisabled(fixture)).toBe(true);

    emitDraft(fixture, { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] });
    expect(saveButtonDisabled(fixture)).toBe(true);

    setNameInput(fixture, 'Depot');
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

  // Prod QA fix: a FLEET_ADMIN must still be able to edit every field.
  it('keeps the name/rule/dwellSecs controls enabled for a FLEET_ADMIN', () => {
    const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_DWELL', dwellSecs: 30, vertices: [] };
    store.setGeofences([existing]);
    const fixture = createFixture();
    click(fixture, 'select-geofence-g1');

    const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
    expect(nameInput.disabled).toBe(false);
    expect(fieldDisabled(fixture, 'geofence-rule-select')).toBe(false);
    expect(fieldDisabled(fixture, 'geofence-dwell-input')).toBe(false);
  });

  // Prod QA fix: selecting a geofence hands it to the drawing editor, which
  // owns the MapLibre map and does the actual fitBounds call (its own spec
  // covers that); this only proves the wiring.
  it('passes the selected geofence down to the drawing editor', () => {
    const existing: GeofenceResponse = {
      id: 'g1',
      name: 'Depot',
      vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }],
    };
    store.setGeofences([existing]);
    const fixture = createFixture();

    click(fixture, 'select-geofence-g1');

    const editor = fixture.debugElement.query(By.directive(FakeGeofenceDrawingEditorComponent))
      .componentInstance as FakeGeofenceDrawingEditorComponent;
    expect(editor.selectedGeofence()).toEqual(existing);
  });

  // Regression, editing mode: canSave must re-evaluate on every form status
  // change while editing too, not just when `draft()` changes (no draft is
  // ever emitted while editing -- see buildUpdateRequest).
  it('re-evaluates Save while editing when the name is cleared and re-entered', () => {
    const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_ENTER', vertices: [] };
    store.setGeofences([existing]);
    const fixture = createFixture();
    click(fixture, 'select-geofence-g1');
    expect(saveButtonDisabled(fixture)).toBe(false);

    setNameInput(fixture, '');
    expect(saveButtonDisabled(fixture)).toBe(true);

    setNameInput(fixture, 'Depot renamed');
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

  it('save() maps a 403 response to a permission message, leaving other errors with the generic message', () => {
    geofenceService.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    const fixture = createFixture();
    setNameInput(fixture, 'Depot');
    emitDraft(fixture, { shape: 'POLYGON', vertices: [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }, { lat: 3, lon: 1 }] });

    clickButton(fixture, 'save-geofence');

    expect(fixture.nativeElement.textContent).toContain("You don't have permission to manage geofences.");
    expect(fixture.nativeElement.textContent).not.toContain('Failed to save the geofence');
  });

  // Dispatcher UX: backend create/update/delete require FLEET_ADMIN
  // (GeofenceController). A non-admin gets a read-only list/map instead of
  // controls whose request would just 403.
  describe('role-based access', () => {
    beforeEach(() => {
      authStore.setDispatcher(DISPATCHER);
    });

    it('hides the "New" button and the Save/Update button for a non-admin', () => {
      const fixture = createFixture();

      expect(fixture.debugElement.query(By.css('[data-testid="new-geofence"]'))).toBeNull();
      expect(fixture.debugElement.query(By.css('[data-testid="save-geofence"]'))).toBeNull();
    });

    it('marks the drawing editor read-only for a non-admin', () => {
      const fixture = createFixture();

      const editor = fixture.debugElement.query(By.directive(FakeGeofenceDrawingEditorComponent))
        .componentInstance as FakeGeofenceDrawingEditorComponent;
      expect(editor.readOnly()).toBe(true);
    });

    it('still shows the list and the selected geofence in the form for viewing', () => {
      const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_ENTER', vertices: [] };
      store.setGeofences([existing]);
      const fixture = createFixture();

      click(fixture, 'select-geofence-g1');

      const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
      expect(nameInput.value).toBe('Depot');
    });

    // Prod QA fix: the fields were previously still editable for a
    // dispatcher even though Save/New were hidden -- a request would just
    // 403, but the UI implied the edit was possible.
    it('disables the name/rule/dwellSecs controls for a non-admin', () => {
      const existing: GeofenceResponse = { id: 'g1', name: 'Depot', rule: 'ON_DWELL', dwellSecs: 30, vertices: [] };
      store.setGeofences([existing]);
      const fixture = createFixture();

      click(fixture, 'select-geofence-g1');

      const nameInput: HTMLInputElement = fixture.nativeElement.querySelector('[data-testid="geofence-name-input"]');
      expect(nameInput.disabled).toBe(true);
      expect(fieldDisabled(fixture, 'geofence-rule-select')).toBe(true);
      expect(fieldDisabled(fixture, 'geofence-dwell-input')).toBe(true);
    });

    it('shows the "New" button, the drawing editor without readOnly, and the Save button again for a FLEET_ADMIN', () => {
      authStore.setDispatcher(FLEET_ADMIN);
      const fixture = createFixture();

      expect(fixture.debugElement.query(By.css('[data-testid="new-geofence"]'))).not.toBeNull();
      expect(fixture.debugElement.query(By.css('[data-testid="save-geofence"]'))).not.toBeNull();
      const editor = fixture.debugElement.query(By.directive(FakeGeofenceDrawingEditorComponent))
        .componentInstance as FakeGeofenceDrawingEditorComponent;
      expect(editor.readOnly()).toBe(false);
    });
  });
});
