// The deploy-console CI job substitutes __PROD_API_URL__ with vars.PROD_API_URL
// before building (single source of truth, no hardcoded IP drifting from the
// repo variable verify-deploy already reads).
export const environment = {
  production: true,
  apiUrl: '__PROD_API_URL__',
};
