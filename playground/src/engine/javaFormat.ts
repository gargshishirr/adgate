/**
 * Number and time formatting that matches what the Java engine produces with
 * String.format(Locale.ROOT, ...), so ported detail strings are identical.
 */

/**
 * Java's "%.{fractionDigits}f": Formatter rounds the shortest decimal representation of the double with
 * RoundingMode.HALF_UP. That differs from Number.prototype.toFixed, which rounds the exact binary value:
 * Java formats 1.005 as "1.01" and 0.35 as "0.4", while toFixed gives "1.00" and "0.3".
 */
export function formatFixed(value: number, fractionDigits: number): string {
  if (Number.isNaN(value)) {
    return 'NaN';
  }
  if (!Number.isFinite(value)) {
    return value > 0 ? 'Infinity' : '-Infinity';
  }
  const negative = value < 0 || Object.is(value, -0);
  // toExponential() without an argument yields the shortest digits that round-trip, like Double.toString.
  const [mantissa = '0', exponentText = '0'] = Math.abs(value).toExponential().split('e');
  const digits = mantissa.replace('.', '');
  const scale = digits.length - 1 - Number(exponentText); // value = digits / 10^scale
  let scaled = BigInt(digits);
  if (scale <= fractionDigits) {
    scaled *= 10n ** BigInt(fractionDigits - scale);
  } else {
    const divisor = 10n ** BigInt(scale - fractionDigits);
    const remainder = scaled % divisor;
    scaled /= divisor;
    if (remainder * 2n >= divisor) {
      scaled += 1n;
    }
  }
  let text = scaled.toString();
  if (fractionDigits > 0) {
    text = text.padStart(fractionDigits + 1, '0');
    text = `${text.slice(0, -fractionDigits)}.${text.slice(-fractionDigits)}`;
  }
  return negative ? `-${text}` : text;
}

/** DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC). */
export function hourMinuteUtc(epochMillis: number): string {
  const date = new Date(epochMillis);
  return `${pad2(date.getUTCHours())}:${pad2(date.getUTCMinutes())}Z`;
}

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/** Character.isWhitespace: separators except the non-breaking ones, plus the ASCII/ISO controls Java counts. */
function isJavaWhitespace(codePoint: number): boolean {
  return (
    (codePoint >= 0x09 && codePoint <= 0x0d) ||
    (codePoint >= 0x1c && codePoint <= 0x20) ||
    codePoint === 0x1680 ||
    (codePoint >= 0x2000 && codePoint <= 0x200a && codePoint !== 0x2007) ||
    codePoint === 0x2028 ||
    codePoint === 0x2029 ||
    codePoint === 0x205f ||
    codePoint === 0x3000
  );
}

/** String.isBlank(). */
export function isJavaBlank(text: string): boolean {
  for (const char of text) {
    if (!isJavaWhitespace(char.codePointAt(0) ?? 0)) {
      return false;
    }
  }
  return true;
}
