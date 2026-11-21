import type { ComponentChildren } from 'preact';

import { isFailClosed } from '../engine/reasonCodes';
import type { ReasonCode } from '../engine/types';
import { REASON_SHORT } from './format';
import { IconCheck, IconLock, IconX } from './icons';

export type Tone = 'serve' | 'decline' | 'failclosed';

export function toneOf(reason: ReasonCode): Tone {
  if (reason === 'ELIGIBLE') {
    return 'serve';
  }
  return isFailClosed(reason) ? 'failclosed' : 'decline';
}

export function Switch(props: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  id?: string;
  describedBy?: string;
  tone?: 'accent' | 'danger';
}) {
  return (
    <button
      type="button"
      role="switch"
      id={props.id}
      class={`switch ${props.tone === 'danger' ? 'switch--danger' : ''}`}
      aria-checked={props.checked}
      aria-label={props.label}
      aria-describedby={props.describedBy}
      onClick={() => props.onChange(!props.checked)}
    >
      <span class="switch__thumb" />
    </button>
  );
}

export function Segmented<T extends string | number>(props: {
  label: string;
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
  size?: 'small';
}) {
  return (
    <div class={`segmented ${props.size === 'small' ? 'segmented--small' : ''}`} role="group" aria-label={props.label}>
      {props.options.map((option) => (
        <button
          type="button"
          key={String(option.value)}
          class="segmented__option"
          aria-pressed={option.value === props.value}
          onClick={() => props.onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function ToggleChip(props: { pressed: boolean; onToggle: () => void; children: ComponentChildren; title?: string }) {
  return (
    <button type="button" class="chip chip--toggle" aria-pressed={props.pressed} title={props.title} onClick={props.onToggle}>
      {props.pressed ? <IconCheck size={13} /> : null}
      {props.children}
    </button>
  );
}

/** SERVE / DECLINE badge; fail-closed declines get a lock so colour is never the only signal. */
export function VerdictBadge({ reason }: { reason: ReasonCode }) {
  const tone = toneOf(reason);
  return (
    <span class={`verdict verdict--${tone}`}>
      {tone === 'serve' ? <IconCheck size={13} /> : tone === 'failclosed' ? <IconLock size={13} /> : <IconX size={13} />}
      {tone === 'serve' ? 'Serve' : 'Decline'}
    </span>
  );
}

export function ReasonChip({ reason, compact = false }: { reason: ReasonCode; compact?: boolean }) {
  const tone = toneOf(reason);
  return (
    <span class={`reason reason--${tone}`} title={reason}>
      {compact ? REASON_SHORT[reason] : reason}
    </span>
  );
}

export function CampaignMark({ name, color, size = 32 }: { name: string; color: string; size?: number }) {
  return (
    <span class="mark" style={{ background: color, width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.45)}px` }} aria-hidden="true">
      {name.slice(0, 1)}
    </span>
  );
}

export function Panel(props: {
  id: string;
  title: string;
  kicker?: string;
  actions?: ComponentChildren;
  children: ComponentChildren;
  class?: string;
}) {
  return (
    <section class={`panel ${props.class ?? ''}`} id={props.id} aria-labelledby={`${props.id}-title`}>
      <header class="panel__header">
        <div>
          {props.kicker ? <p class="panel__kicker">{props.kicker}</p> : null}
          <h2 class="panel__title" id={`${props.id}-title`}>
            {props.title}
          </h2>
        </div>
        {props.actions ? <div class="panel__actions">{props.actions}</div> : null}
      </header>
      {props.children}
    </section>
  );
}
