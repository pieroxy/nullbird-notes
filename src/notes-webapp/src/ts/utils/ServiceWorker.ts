/** Its URL must stay the same: the browser keeps it, and checks it for updates (see src/pwa/sw.js). */
const SW_URL = "/sw.js";
/** Allowed by the CSP's trusted-types directive (see SecurityHeadersFilter): the only one. */
const POLICY = "notes-sw";

/** The part of Trusted Types used here: TypeScript's DOM types don't have it yet. */
interface TrustedTypesWindow {
  trustedTypes?: {
    createPolicy(name: string, rules: { createScriptURL(url: string): string }): { createScriptURL(url: string): unknown };
  };
}

/**
 * Registers the service worker, which keeps the app on the device so that it opens offline.
 * Browsers only allow it over HTTPS or on localhost: elsewhere, the app works as before, online.
 */
export function registerServiceWorker() {
  if (!("serviceWorker" in navigator)) return;
  const trustedTypes = (window as TrustedTypesWindow).trustedTypes;
  const url = trustedTypes
    ? trustedTypes.createPolicy(POLICY, {
      createScriptURL: (candidate) => {
        if (candidate !== SW_URL) throw new Error("Not the service worker: " + candidate);
        return candidate;
      },
    }).createScriptURL(SW_URL)
    : SW_URL;
  // The Trusted Types object stands for the URL: register() takes either.
  navigator.serviceWorker.register(url as string, { scope: "/" }).catch((err: unknown) => {
    console.warn("The service worker couldn't be registered: the app won't open offline.", err);
  });
}
