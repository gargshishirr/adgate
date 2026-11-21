import type { Milestone } from '../sim/simulation';
import { count } from './format';
import { IconCheck, IconGitHub, IconShield } from './icons';

export const REPO_URL = 'https://github.com/gargshishirr/adgate';

export function Header() {
  return (
    <header class="hero">
      <div class="hero__bar">
        <a class="brand" href={REPO_URL} aria-label="AdGate on GitHub">
          <span class="brand__logo" aria-hidden="true">
            <svg viewBox="0 0 32 32" width="28" height="28">
              <rect width="32" height="32" rx="8" fill="currentColor" />
              <path d="M9 23V12.5a7 7 0 0 1 14 0V23" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" />
              <path d="M13 23v-6h6v6" fill="none" stroke="#fff" stroke-width="2.6" stroke-linejoin="round" />
            </svg>
          </span>
          <span class="brand__name">AdGate</span>
          <span class="brand__tag">Playground</span>
        </a>
        <a class="button button--ghost" href={REPO_URL} target="_blank" rel="noopener noreferrer">
          <IconGitHub size={16} />
          <span>View on GitHub</span>
        </a>
      </div>
      <div class="hero__body">
        <h1 class="hero__title">Which ads may serve, decided in milliseconds.</h1>
        <p class="hero__lead">
          AdGate is the real-time gate an ad server calls on every request: it decides which candidate ads may serve by
          enforcing budgets, even pacing, placement and keyword policy and traffic quality, and it fails closed, with a
          reason code, whenever it cannot prove an ad is safe to serve.
        </p>
        <p class="hero__note">
          <IconShield size={18} class="hero__note-icon" />
          <span>
            Runs AdGate's real decision rules — ported to TypeScript and verified against the Java engine with{' '}
            <strong>{count(__GOLDEN_VECTOR_COUNT__)} golden test vectors</strong> — in your browser. The production service is
            Java 21 + Spring Boot with Kafka and Redis.{' '}
            <a href={`${REPO_URL}#readme`} target="_blank" rel="noopener noreferrer">
              Read how it works
            </a>
          </span>
        </p>
      </div>
    </header>
  );
}

const STEPS: readonly { milestone: Milestone; title: string; hint: string; target: string }[] = [
  { milestone: 'sent', title: 'Send an ad request', hint: 'Try the query “free photo editor”.', target: 'send-button' },
  { milestone: 'traffic', title: 'Start live traffic', hint: 'Watch decisions stream in.', target: 'traffic-toggle' },
  { milestone: 'fastForward', title: 'Fast-forward the day', hint: 'Spend tracks the pacing line.', target: 'speed-control' },
  { milestone: 'chaos', title: 'Flip a chaos switch', hint: 'See it fail closed, then recover.', target: 'chaos-redisDown' },
  { milestone: 'explain', title: 'Explain a decision', hint: 'Click any decision.', target: 'feed-list' },
];

export function TryThis({ done }: { done: ReadonlySet<Milestone> }) {
  const completed = STEPS.filter((step) => done.has(step.milestone)).length;
  return (
    <nav class="try" aria-labelledby="try-title">
      <div class="try__head">
        <h2 class="try__title" id="try-title">
          Try this
        </h2>
        <span class="try__progress" aria-live="polite">
          {completed === STEPS.length ? 'All done. Nice!' : `${completed} of ${STEPS.length} done`}
        </span>
      </div>
      <ol class="try__steps">
        {STEPS.map((step, index) => {
          const isDone = done.has(step.milestone);
          return (
            <li key={step.milestone}>
              <button
                type="button"
                class={`try__step ${isDone ? 'try__step--done' : ''}`}
                onClick={() => focusTarget(step.target)}
                aria-label={`Step ${index + 1}: ${step.title}${isDone ? ' (done)' : ''}. ${step.hint}`}
              >
                <span class="try__num" aria-hidden="true">
                  {isDone ? <IconCheck size={14} /> : index + 1}
                </span>
                <span class="try__text">
                  <span class="try__step-title">{step.title}</span>
                  <span class="try__hint">{step.hint}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

function focusTarget(id: string): void {
  const element = document.getElementById(id);
  if (!element) {
    return;
  }
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  element.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
  const focusable = element.matches('button, input, select, a, [tabindex]')
    ? element
    : element.querySelector<HTMLElement>('button, input, select, a, [tabindex]');
  focusable?.focus({ preventScroll: true });
}
