import { TestBed } from '@angular/core/testing';
import { ConnectionStatusComponent } from './connection-status.component';

describe('ConnectionStatusComponent', () => {
  function render(status: 'disconnected' | 'connecting' | 'connected' | 'reconnecting') {
    const fixture = TestBed.createComponent(ConnectionStatusComponent);
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
    return fixture;
  }

  // Task 5.4
  it('renders a success tag with a human-readable label when connected', async () => {
    const fixture = render('connected');
    await fixture.whenStable();

    const tag = fixture.nativeElement.querySelector('[data-testid="connection-status"]') as HTMLElement;
    expect(tag.textContent).toContain('Connected');
  });

  it('renders a danger tag when disconnected', async () => {
    const fixture = render('disconnected');
    await fixture.whenStable();

    const tag = fixture.nativeElement.querySelector('[data-testid="connection-status"]') as HTMLElement;
    expect(tag.textContent).toContain('Disconnected');
  });

  it('renders a warn tag while (re)connecting', async () => {
    const reconnecting = render('reconnecting');
    await reconnecting.whenStable();
    expect(reconnecting.nativeElement.textContent).toContain('Reconnecting');

    const connecting = render('connecting');
    await connecting.whenStable();
    expect(connecting.nativeElement.textContent).toContain('Connecting');
  });
});
