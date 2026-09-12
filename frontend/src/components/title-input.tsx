import { Box } from "@radix-ui/themes";
import { useCallback, useEffect, useLayoutEffect, useRef } from "react";
import { useTranslation } from "react-i18next";

import { useMobileViewport } from "@/hooks/use-mobile-viewport";

const MOBILE_TITLE_LONG_PRESS_MS = 280;
const MOBILE_TITLE_MOVE_TOLERANCE = 8;

export interface TitleInputProps {
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  disabled?: boolean;
  onDisabledClick?: () => void;
  placeholder?: string;
}

/**
 * Title field for documents and world-book entries.
 *
 * Desktop keeps a single-line `<input>`: the field is wide, titles fit, and horizontal
 * scrolling while typing is the familiar behaviour. At phone width the same field is about
 * a third as wide, so a real title like "战略地理：汴京、河北、河东、燕云、陕西、江淮" only
 * ever showed its first few characters — an input cannot wrap. There it becomes a
 * self-sizing `<textarea>` at a smaller size so the whole title stays visible.
 */
export function TitleInput({
  value,
  onChange,
  onBlur,
  disabled = false,
  onDisabledClick,
  placeholder,
}: TitleInputProps) {
  const { t } = useTranslation();
  const isMobileViewport = useMobileViewport();
  const fieldRef = useRef<HTMLInputElement | HTMLTextAreaElement>(null);
  const longPressTimerRef = useRef<number | null>(null);
  const pointerRef = useRef<{
    pointerId: number;
    x: number;
    y: number;
    isLongPress: boolean;
  } | null>(null);

  const clearLongPress = useCallback(() => {
    if (longPressTimerRef.current !== null) {
      window.clearTimeout(longPressTimerRef.current);
      longPressTimerRef.current = null;
    }
  }, []);

  const clearPointer = useCallback(() => {
    clearLongPress();
    pointerRef.current = null;
  }, [clearLongPress]);

  useEffect(() => clearPointer, [clearPointer]);

  // Grow the wrapping field to fit its content; a fixed `rows` would either clip the title
  // or leave a tall empty box under it.
  useLayoutEffect(() => {
    const field = fieldRef.current;
    if (!field || field.tagName !== "TEXTAREA") return;
    field.style.height = "auto";
    field.style.height = `${field.scrollHeight}px`;
  }, [value, isMobileViewport]);

  const handlePointerDown = useCallback(
    (event: React.PointerEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      if (event.pointerType === "mouse" || event.button !== 0 || disabled) return;

      clearPointer();
      pointerRef.current = {
        pointerId: event.pointerId,
        x: event.clientX,
        y: event.clientY,
        isLongPress: false,
      };

      longPressTimerRef.current = window.setTimeout(() => {
        if (!pointerRef.current) return;
        pointerRef.current.isLongPress = true;
        fieldRef.current?.blur();
      }, MOBILE_TITLE_LONG_PRESS_MS);
    },
    [clearPointer, disabled],
  );

  const handlePointerMove = useCallback(
    (event: React.PointerEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const pointer = pointerRef.current;
      if (!pointer || pointer.pointerId !== event.pointerId) return;

      const hasMoved =
        Math.abs(event.clientX - pointer.x) > MOBILE_TITLE_MOVE_TOLERANCE ||
        Math.abs(event.clientY - pointer.y) > MOBILE_TITLE_MOVE_TOLERANCE;
      if (hasMoved && !pointer.isLongPress) {
        clearPointer();
      }
    },
    [clearPointer],
  );

  const handlePointerUp = useCallback(
    (event: React.PointerEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const pointer = pointerRef.current;
      if (!pointer || pointer.pointerId !== event.pointerId) return;

      clearLongPress();
      if (pointer.isLongPress) {
        event.preventDefault();
        event.stopPropagation();
        fieldRef.current?.blur();
      }
      pointerRef.current = null;
    },
    [clearLongPress],
  );

  const handleContextMenu = useCallback(
    (event: React.MouseEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      if (!pointerRef.current?.isLongPress) return;
      event.preventDefault();
      event.stopPropagation();
      fieldRef.current?.blur();
    },
    [],
  );

  // A title is a single logical line even when it wraps: Enter commits rather than
  // inserting a newline the rest of the app would have to strip.
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.key !== "Enter") return;
      event.preventDefault();
      fieldRef.current?.blur();
    },
    [],
  );

  const handleChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      onChange(event.target.value);
    },
    [onChange],
  );

  const handleDisabledClick = disabled
    ? (event: React.MouseEvent) => {
        event.preventDefault();
        onDisabledClick?.();
      }
    : undefined;

  const handleDisabledFocus = disabled
    ? (event: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
        event.target.blur();
        onDisabledClick?.();
      }
    : undefined;

  const sharedProps = {
    value,
    onChange: handleChange,
    onBlur,
    onPointerDown: handlePointerDown,
    onPointerMove: handlePointerMove,
    onPointerUp: handlePointerUp,
    onPointerCancel: clearPointer,
    onContextMenu: handleContextMenu,
    onClick: handleDisabledClick,
    onFocus: handleDisabledFocus,
    placeholder: placeholder ?? t("writing.titlePlaceholder"),
    readOnly: disabled,
  };

  const sharedStyle: React.CSSProperties = {
    width: "100%",
    border: "none",
    outline: "none",
    background: "transparent",
    fontWeight: 700,
    lineHeight: 1.3,
    color: "var(--gray-12)",
    padding: 0,
    cursor: disabled ? "not-allowed" : "text",
  };

  return (
    <Box py="5">
      {isMobileViewport ? (
        <textarea
          {...sharedProps}
          ref={fieldRef as React.RefObject<HTMLTextAreaElement>}
          rows={1}
          onKeyDown={handleKeyDown}
          style={{
            ...sharedStyle,
            fontSize: "calc(var(--font-size-editor) * 1.5)",
            resize: "none",
            overflow: "hidden",
            // The box height is driven by the layout effect above.
            height: "auto",
          }}
        />
      ) : (
        <input
          {...sharedProps}
          ref={fieldRef as React.RefObject<HTMLInputElement>}
          type="text"
          style={{
            ...sharedStyle,
            fontSize: "calc(var(--font-size-editor) * 2)",
          }}
        />
      )}
    </Box>
  );
}
