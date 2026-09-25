import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Tag } from 'primeng/tag';
import type { ConnectionStatusValue } from '../models/vehicle-view.model';

type TagSeverity = 'success' | 'warn' | 'danger';

const STATUS_LABEL: Record<ConnectionStatusValue, string> = {
  connected: 'Conectado',
  connecting: 'Conectando…',
  reconnecting: 'Reconectando…',
  disconnected: 'Desconectado',
};

const STATUS_SEVERITY: Record<ConnectionStatusValue, TagSeverity> = {
  connected: 'success',
  connecting: 'warn',
  reconnecting: 'warn',
  disconnected: 'danger',
};

// Task 5.4: reads WU1's `MqttConnectionService.status` signal (via the
// container's `[status]` binding) and renders it as a badge -- this
// component never touches the MQTT client itself, matching the
// container/presentational split.
@Component({
  selector: 'console-ui-connection-status',
  imports: [Tag],
  templateUrl: './connection-status.component.html',
  styleUrl: './connection-status.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConnectionStatusComponent {
  readonly status = input.required<ConnectionStatusValue>();

  protected readonly label = computed(() => STATUS_LABEL[this.status()]);
  protected readonly severity = computed(() => STATUS_SEVERITY[this.status()]);
}
