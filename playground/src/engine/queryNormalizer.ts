import { isJavaBlank } from './javaFormat';

/**
 * Port of QueryNormalizer.java, used by negative-keyword policy: Unicode compatibility folding (NFKD), accent
 * stripping, case folding, tokenization on non-alphanumerics, and light plural stemming. Queries and keywords go
 * through the same pipeline, so "FREE Gamés!" matches the negative keyword "free game".
 */

const COMBINING_MARKS = /\p{M}+/gu;
const SEPARATORS = /[^\p{L}\p{N}]+/u;

export function tokens(text: string | null | undefined): string[] {
  if (text == null || isJavaBlank(text)) {
    return [];
  }
  const folded = text.normalize('NFKD').replace(COMBINING_MARKS, '').toLowerCase();
  const result: string[] = [];
  for (const token of folded.split(SEPARATORS)) {
    if (token.length > 0) {
      result.push(stem(token));
    }
  }
  return result;
}

/** True if phrase occurs in tokens as a contiguous token sequence. */
export function containsPhrase(queryTokens: readonly string[], phrase: readonly string[]): boolean {
  if (phrase.length === 0 || phrase.length > queryTokens.length) {
    return false;
  }
  for (let start = 0; start + phrase.length <= queryTokens.length; start++) {
    let matches = true;
    for (let i = 0; i < phrase.length; i++) {
      if (queryTokens[start + i] !== phrase[i]) {
        matches = false;
        break;
      }
    }
    if (matches) {
      return true;
    }
  }
  return false;
}

export function stem(token: string): string {
  const length = token.length;
  if (length > 4 && token.endsWith('ies')) {
    return `${token.substring(0, length - 3)}y`;
  }
  if (length > 4 && token.endsWith('es')) {
    const base = token.substring(0, length - 2);
    if (base.endsWith('s') || base.endsWith('x') || base.endsWith('z') || base.endsWith('ch') || base.endsWith('sh')) {
      return base;
    }
  }
  if (length > 3 && token.endsWith('s') && !token.endsWith('ss') && !token.endsWith('us') && !token.endsWith('is')) {
    return token.substring(0, length - 1);
  }
  return token;
}
