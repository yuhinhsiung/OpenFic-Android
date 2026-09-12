import NumberFlow from "@number-flow/react";
import type { ComponentProps } from "react";

type NumberFlowProps = ComponentProps<typeof NumberFlow>;

/**
 * `@number-flow/react` builds each digit as a vertical `0123456789` strip and clips it with
 * a CSS mask, compensating the mask's footprint with a negative margin. Both the mask and
 * the digit offset are computed with the CSS math functions `round()` and `mod()`, which
 * only landed in Chromium 125.
 *
 * On an older engine those functions make the declarations invalid, so the mask silently
 * resolves to `none` and the strip's width compensation disappears: the host element ends
 * up narrower than the digits it contains — measured as small as zero — and the number
 * overlaps whatever follows it. Android WebViews older than 125 are still common on
 * devices without Play Store access, where WebView cannot be updated at all.
 *
 * Where the engine can't lay the component out, render the same formatted value as plain
 * text. Losing the rolling animation is a far smaller cost than garbled numbers.
 */
const NUMBER_FLOW_LAYOUT_SUPPORTED =
  typeof CSS !== "undefined" &&
  typeof CSS.supports === "function" &&
  CSS.supports("width", "round(nearest, 1px, 1px)") &&
  CSS.supports("width", "mod(10px, 3px)");

export function SafeNumberFlow({
  value,
  locales,
  format,
  suffix,
  className,
  ...rest
}: NumberFlowProps) {
  if (NUMBER_FLOW_LAYOUT_SUPPORTED) {
    return (
      <NumberFlow
        value={value}
        locales={locales}
        format={format}
        suffix={suffix}
        className={className}
        {...rest}
      />
    );
  }

  // Mirrors how NumberFlow formats statically: the value goes through Intl with the same
  // options the caller passed, and the suffix is appended verbatim.
  const text = new Intl.NumberFormat(locales, format).format(value);
  return (
    <span className={className}>
      {text}
      {suffix}
    </span>
  );
}
