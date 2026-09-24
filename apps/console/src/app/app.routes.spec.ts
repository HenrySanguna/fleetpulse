import { appRoutes } from './app.routes';

describe('appRoutes', () => {
  it('redirects unknown paths to the root route', () => {
    const wildcard = appRoutes[appRoutes.length - 1];

    expect(wildcard?.path).toBe('**');
    expect(wildcard?.redirectTo).toBe('');
  });
});
