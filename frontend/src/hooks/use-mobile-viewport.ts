import { useEffect, useState } from "react";

/**
 * The breakpoint the app shell uses to switch between the mobile and desktop layouts.
 * Kept here so shared components can match it without depending on the app-shell feature
 * (which imports from `@/components`, so the reverse import would be a cycle).
 */
export const MOBILE_VIEWPORT_QUERY = "(max-width: 767px)";

function matchesMobileViewport(): boolean {
  if (typeof window === "undefined") return false;
  return window.matchMedia(MOBILE_VIEWPORT_QUERY).matches;
}

/**
 * True while the viewport is at a phone width.
 *
 * Uses `matchMedia` rather than a resize listener: it fires once per crossing instead of on
 * every resize event, which matters because callers re-render on the result.
 */
export function useMobileViewport(): boolean {
  const [isMobile, setIsMobile] = useState(matchesMobileViewport);

  useEffect(() => {
    const query = window.matchMedia(MOBILE_VIEWPORT_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setIsMobile(event.matches);
    query.addEventListener("change", handleChange);
    // The viewport may have crossed the breakpoint between the initial render and this
    // effect running (hydration, fast rotation), so re-read once on subscribe.
    setIsMobile(query.matches);
    return () => query.removeEventListener("change", handleChange);
  }, []);

  return isMobile;
}
