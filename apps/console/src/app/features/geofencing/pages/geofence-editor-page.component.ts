import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators, type AbstractControl, type ValidationErrors } from '@angular/forms';
import { Button } from 'primeng/button';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Select } from 'primeng/select';
import { GeofenceRequest, type GeofenceResponse } from '@fleetpulse/api-client';
import { GeofenceDrawingEditorComponent } from '../components/geofence-drawing-editor.component';
import type { GeofenceDraft } from '../models/geofence-draft.model';
import { openRing, toGeoPointRequests } from '../services/geofence-geometry.util';
import { GeofenceService } from '../services/geofence.service';
import { GeofenceStore } from '../services/geofence.store';

interface GeofenceFormControls {
  name: FormControl<string>;
  rule: FormControl<GeofenceRequest.RuleEnum>;
  dwellSecs: FormControl<number | null>;
}

// PrimeNG's Select `options` input is typed as a mutable array, so this
// cannot be `ReadonlyArray` even though nothing here ever mutates it.
const RULE_OPTIONS: Array<{ label: string; value: GeofenceRequest.RuleEnum }> = [
  { label: 'On enter', value: GeofenceRequest.RuleEnum.OnEnter },
  { label: 'On exit', value: GeofenceRequest.RuleEnum.OnExit },
  { label: 'On dwell', value: GeofenceRequest.RuleEnum.OnDwell },
];

// Mirrors GeofenceService's (backend, WU6) own cross-field check --
// dwellSecs is only meaningful, and only required, for the on_dwell rule.
// Bean Validation's server-side check is still the authority; this only
// gives the dispatcher same-page feedback instead of a round-trip 400.
function dwellSecsRequiredForOnDwell(group: AbstractControl): ValidationErrors | null {
  const rule = group.get('rule')?.value;
  const dwellSecs = group.get('dwellSecs')?.value;
  return rule === GeofenceRequest.RuleEnum.OnDwell && !dwellSecs ? { dwellSecsRequired: true } : null;
}

function defaultFormValue(geofence?: GeofenceResponse): { name: string; rule: GeofenceRequest.RuleEnum; dwellSecs: number | null } {
  return {
    name: geofence?.name ?? '',
    rule: geofence?.rule ?? GeofenceRequest.RuleEnum.OnEnter,
    dwellSecs: geofence?.dwellSecs ?? null,
  };
}

// Tasks 5.1-5.3 (+ 5.2 console half). Container: owns the reactive form,
// the existing-geofences list, and turning a committed GeofenceDraft (from
// GeofenceDrawingEditorComponent) plus the form's own fields into a
// GeofenceRequest for the existing CRUD backend (WU6) -- the drawing editor
// itself has no knowledge of name/rule/dwellSecs or HTTP.
@Component({
  selector: 'app-geofence-editor-page',
  imports: [ReactiveFormsModule, Button, InputNumber, InputText, Select, GeofenceDrawingEditorComponent],
  templateUrl: './geofence-editor-page.component.html',
  styleUrl: './geofence-editor-page.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeofenceEditorPageComponent implements OnInit {
  private readonly geofenceService = inject(GeofenceService);
  private readonly store = inject(GeofenceStore);

  protected readonly geofences = this.store.geofences;
  protected readonly loading = this.store.loading;
  protected readonly loadError = this.store.error;
  protected readonly selected = this.store.selected;
  protected readonly ruleOptions = RULE_OPTIONS;
  protected readonly ruleEnum = GeofenceRequest.RuleEnum;

  protected readonly resetToken = signal(0);
  protected readonly draft = signal<GeofenceDraft | undefined>(undefined);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | undefined>(undefined);

  protected readonly form = new FormGroup<GeofenceFormControls>(
    {
      name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(255)] }),
      rule: new FormControl(GeofenceRequest.RuleEnum.OnEnter, { nonNullable: true, validators: [Validators.required] }),
      dwellSecs: new FormControl<number | null>(null, { validators: [Validators.min(1)] }),
    },
    { validators: dwellSecsRequiredForOnDwell },
  );

  protected readonly isEditing = computed(() => this.selected() !== undefined);
  // A new geofence needs a drawn shape; an edit only ever resends the
  // selected geofence's own existing vertices (see buildUpdateRequest).
  protected readonly canSave = computed(() => {
    if (this.saving()) {
      return false;
    }
    return this.isEditing() ? this.form.valid : this.draft() !== undefined && this.form.valid;
  });

  ngOnInit(): void {
    this.geofenceService.load();
  }

  protected onDraftChange(draft: GeofenceDraft | undefined): void {
    this.draft.set(draft);
  }

  protected selectGeofence(geofence: GeofenceResponse): void {
    this.store.select(geofence.id);
    this.form.reset(defaultFormValue(geofence));
    this.resetToken.update((n) => n + 1);
    this.saveError.set(undefined);
  }

  protected startNewGeofence(): void {
    this.store.select(undefined);
    this.form.reset(defaultFormValue());
    this.resetToken.update((n) => n + 1);
    this.saveError.set(undefined);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const selected = this.selected();
    const request = selected ? this.buildUpdateRequest(selected) : this.buildCreateRequest();
    if (!request) {
      return;
    }

    this.saving.set(true);
    this.saveError.set(undefined);
    const save$ = selected?.id ? this.geofenceService.update(selected.id, request) : this.geofenceService.create(request);
    save$.subscribe({
      next: () => {
        this.saving.set(false);
        this.startNewGeofence();
      },
      error: (error: unknown) => {
        console.error('GeofenceEditorPageComponent: failed to save geofence', error);
        this.saving.set(false);
        this.saveError.set('Failed to save the geofence. Check the shape and try again.');
      },
    });
  }

  private buildCreateRequest(): GeofenceRequest | undefined {
    const draft = this.draft();
    if (!draft) {
      return undefined;
    }
    const geometry: Pick<GeofenceRequest, 'shape' | 'vertices' | 'center' | 'radiusMeters'> =
      draft.shape === 'CIRCLE'
        ? { shape: GeofenceRequest.ShapeEnum.Circle, center: { lat: draft.center.lat, lon: draft.center.lon }, radiusMeters: draft.radiusMeters }
        : { shape: GeofenceRequest.ShapeEnum.Polygon, vertices: toGeoPointRequests(draft.vertices) };
    return { ...this.formValueFields(), ...geometry };
  }

  // Documented deviation (per this change's "note deviations" convention):
  // GeofenceResponse only ever exposes `vertices` (task 1.3/WU6 -- a
  // circle's own vertices ARE its buffered polygon), so there is no way to
  // recover whether an existing geofence was originally drawn as a circle.
  // An edit always resends its existing vertices verbatim, reclassified as
  // `shape: POLYGON` -- a previously-circular geofence loses its "circle"
  // identity on its first metadata-only edit, even though the stored
  // geometry itself (the same vertices, byte for byte) never changes.
  // Redrawing an existing geofence's geometry is an explicit scope boundary
  // for this WU ("wire the drawing editor's create/update calls"), not a
  // gap -- left for a future change if ever needed.
  private buildUpdateRequest(geofence: GeofenceResponse): GeofenceRequest | undefined {
    const vertices = openRing(geofence.vertices ?? []);
    if (vertices.length < 3) {
      return undefined;
    }
    return { ...this.formValueFields(), shape: GeofenceRequest.ShapeEnum.Polygon, vertices: toGeoPointRequests(vertices) };
  }

  private formValueFields(): Pick<GeofenceRequest, 'name' | 'rule' | 'dwellSecs'> {
    const value = this.form.getRawValue();
    return {
      name: value.name,
      rule: value.rule,
      dwellSecs: value.rule === GeofenceRequest.RuleEnum.OnDwell ? value.dwellSecs ?? undefined : undefined,
    };
  }
}
