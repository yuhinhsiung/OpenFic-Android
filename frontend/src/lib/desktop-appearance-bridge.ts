import type { ThemeMode } from "@/features/settings/lib/settings.types";
import type { LanguageCode } from "@/i18n";

export interface DesktopAppearancePayload {
  appearance?: ThemeMode;
  fontFamily?: string;
  codeFontFamily?: string;
}

export interface SocketDiagnosticPayload {
  event:
    | "connect-start"
    | "connect-error"
    | "reconnect-attempt"
    | "reconnect-failed"
    | "connected"
    | "disconnected"
    | "connection-timeout";
  active?: boolean;
  attempt?: number;
  durationMs?: number;
  message?: string;
  transport?: string;
  url?: string;
}

/**
 * Host bridges report the SPA's appearance/connection state to whatever shell is hosting it.
 * The Android shell implements the same three methods so the code below stays
 * platform-agnostic; every call is optional and silently no-ops without a host.
 */
export interface AppearanceHostBridge {
  publishAppearance?: (payload: DesktopAppearancePayload) => void;
  publishLanguage?: (language: LanguageCode) => void;
  publishSocketDiagnostic?: (payload: SocketDiagnosticPayload) => void;
}

declare global {
  interface Window {
    openficDesktopHost?: {
      publishAppearance: (payload: DesktopAppearancePayload) => void;
      publishLanguage: (language: LanguageCode) => void;
      publishSocketDiagnostic: (payload: SocketDiagnosticPayload) => void;
    };
    openficAndroidHost?: AppearanceHostBridge & {
      /** Opens the native server-address screen; absent on other platforms. */
      openServerSettings?: () => void;
    };
  }
}

export function publishDesktopAppearance(payload: DesktopAppearancePayload): void {
  window.openficDesktopHost?.publishAppearance(payload);
  window.openficAndroidHost?.publishAppearance?.(payload);
}

export function publishDesktopLanguage(language: LanguageCode): void {
  window.openficDesktopHost?.publishLanguage(language);
  window.openficAndroidHost?.publishLanguage?.(language);
}

export function publishSocketDiagnostic(payload: SocketDiagnosticPayload): void {
  window.openficDesktopHost?.publishSocketDiagnostic?.(payload);
  window.openficAndroidHost?.publishSocketDiagnostic?.(payload);
}
