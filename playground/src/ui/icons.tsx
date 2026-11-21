import type { JSX } from 'preact';

/** Small inline icons (24px grid, stroke-based) so the bundle needs no icon font. */

type IconProps = { size?: number; class?: string };

function Svg({ size = 16, class: className, children }: IconProps & { children: JSX.Element | JSX.Element[] }) {
  return (
    <svg
      class={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export const IconCheck = (props: IconProps) => (
  <Svg {...props}>
    <path d="M20 6 9 17l-5-5" />
  </Svg>
);

export const IconX = (props: IconProps) => (
  <Svg {...props}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Svg>
);

export const IconPlay = (props: IconProps) => (
  <Svg {...props}>
    <path d="M7 4.5v15l12-7.5z" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconPause = (props: IconProps) => (
  <Svg {...props}>
    <path d="M8 5v14M16 5v14" stroke-width="3" />
  </Svg>
);

export const IconForward = (props: IconProps) => (
  <Svg {...props}>
    <path d="M4 6v12l8-6zM13 6v12l8-6z" fill="currentColor" stroke="none" />
  </Svg>
);

export const IconSend = (props: IconProps) => (
  <Svg {...props}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Svg>
);

export const IconGitHub = (props: IconProps) => (
  <svg
    class={props.class}
    width={props.size ?? 16}
    height={props.size ?? 16}
    viewBox="0 0 16 16"
    fill="currentColor"
    aria-hidden="true"
    focusable="false"
  >
    <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
  </svg>
);

export const IconShield = (props: IconProps) => (
  <Svg {...props}>
    <path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.4 7.5 9.5 4.3-1.1 7.5-4.9 7.5-9.5V6z" />
    <path d="m8.8 12.2 2.2 2.2 4.4-4.6" />
  </Svg>
);

export const IconLock = (props: IconProps) => (
  <Svg {...props}>
    <rect x="5" y="11" width="14" height="10" rx="2" />
    <path d="M8 11V8a4 4 0 0 1 8 0v3" />
  </Svg>
);

export const IconBolt = (props: IconProps) => (
  <Svg {...props}>
    <path d="M13 2 4 14h7l-1 8 9-12h-7z" />
  </Svg>
);

export const IconReplay = (props: IconProps) => (
  <Svg {...props}>
    <path d="M3 12a9 9 0 1 0 3-6.7" />
    <path d="M3 4v5h5" />
  </Svg>
);

export const IconPencil = (props: IconProps) => (
  <Svg {...props}>
    <path d="M4 20h4L19 9l-4-4L4 16z" />
  </Svg>
);

export const IconDot = (props: IconProps) => (
  <Svg {...props}>
    <circle cx="12" cy="12" r="2" fill="currentColor" />
  </Svg>
);
