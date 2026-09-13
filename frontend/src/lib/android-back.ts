import { useEffect, useRef } from "react";

/**
 * Back handling for the Android host.
 *
 * The system back key is a native event with no web equivalent. The WebView's own history
 * knows only about route changes, so anything layered *on top of* a route — the sidebar
 * drawer, the settings dialog, the assistant panel — is invisible to it, and a back press
 * there closes the whole app instead of the overlay. (Route changes do work: they push
 * real history entries, which is why back already walks between pages.)
 *
 * So the native side asks this module first and falls back to its own history and then to
 * exiting only when nothing here claims the press. Handlers form a stack: whatever opened
 * last closes first, which is what a back key is expected to do.
 */

type BackHandler = () => void;

interface BackHandlerEntry {
  handler: BackHandler;
}

let nextId = 0;
const stack: { id: number; entry: BackHandlerEntry }[] = [];

/**
 * Claims the back key until the returned function is called. Callers register while their
 * overlay is *open* rather than while it is mounted: the drawer and the assistant panel
 * stay mounted while hidden, and a hidden overlay must not swallow the back key.
 */
export function pushAndroidBackHandler(handler: BackHandler): () => void {
  const id = nextId++;
  stack.push({ id, entry: { handler } });
  return () => {
    const index = stack.findIndex((item) => item.id === id);
    if (index !== -1) stack.splice(index, 1);
  };
}

/** Runs the topmost handler. Returns whether anything claimed the press. */
export function handleAndroidBack(): boolean {
  const top = stack[stack.length - 1];
  if (!top) return false;
  top.entry.handler();
  return true;
}

declare global {
  interface Window {
    /** Called by the Android shell on a back press; returns whether it was handled. */
    __openficHandleBack?: () => boolean;
  }
}

export function useAndroidBackHandler(enabled: boolean, handler: BackHandler): void {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  useEffect(() => {
    if (!enabled) return;
    return pushAndroidBackHandler(() => handlerRef.current());
  }, [enabled]);
}

/**
 * Installs the entry point the native side calls.
 *
 * Gated on the Android host: on the desktop and web builds nothing would ever call it, and
 * leaving a stray global behind invites someone to rely on it.
 */
export function installAndroidBackBridge(): void {
  if (typeof window === "undefined" || !window.openficAndroidHost) return;
  window.__openficHandleBack = handleAndroidBack;
}
