---
name: Cereveil Calm Shelter
colors:
  background: '#F7F3EC'
  on-background: '#20243A'
  surface: '#FFFDF8'
  surface-container-low: '#F3EEE5'
  surface-container: '#ECE6DC'
  surface-container-high: '#E4DED4'
  surface-container-highest: '#DAD4CA'
  on-surface: '#20243A'
  on-surface-variant: '#5D6070'
  outline: '#777986'
  outline-variant: '#CBC6BF'
  primary: '#35406A'
  on-primary: '#FFFFFF'
  primary-container: '#E1E6FF'
  on-primary-container: '#202A50'
  secondary: '#4F7F78'
  on-secondary: '#FFFFFF'
  secondary-container: '#D5EFE9'
  on-secondary-container: '#173F3A'
  tertiary: '#9A604E'
  on-tertiary: '#FFFFFF'
  tertiary-container: '#FFDCCF'
  on-tertiary-container: '#4E1F14'
  error: '#A83B3B'
  on-error: '#FFFFFF'
  error-container: '#FFDAD6'
  on-error-container: '#5B1015'
typography:
  display:
    fontFamily: Plus Jakarta Sans
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
    letterSpacing: -0.02em
  title-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 36px
    letterSpacing: -0.015em
  title-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 20px
    fontWeight: '650'
    lineHeight: 28px
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 17px
    fontWeight: '400'
    lineHeight: 26px
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  label:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '650'
    lineHeight: 20px
rounded:
  sm: 0.5rem
  DEFAULT: 0.75rem
  md: 1rem
  lg: 1.25rem
  xl: 1.75rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 24px
  xxl: 32px
  margin: 20px
---

# Creative north star

Cereveil should feel like a calm shelter: warm, reassuring, transparent, and quietly capable. It is a family-safety product, not a surveillance console. The interface must make sensitive setup feel understandable without trivialising it.

# Brand

Use a typography-only `Cereveil` wordmark in Plus Jakarta Sans with a confident semibold or bold weight and restrained spacing. The product has no standalone symbol, monogram, badge, abstract mark, mascot, or decorative shape. Do not generate a visual logo from arcs, eyes, shields, locks, radar, crosshairs, phones, or family figures.

Guardian screens may carry more detail and operational status. Child screens use the same system with fewer simultaneous choices, larger explanations, and an unmistakable `Child` role label. Use `Guardian`, never `Parent`, in product copy.

# Color and surfaces

Warm parchment is the base rather than pure white. Cards use creamy surfaces with tonal separation and very light ambient shadows. Deep indigo communicates trust and primary action. Muted teal communicates ready, connected, and protected states. Clay is a sparing human accent. Amber is reserved for attention; red is reserved for genuine blocking or critical states.

Never communicate state through color alone. Pair every semantic color with text and an icon.

# Typography

Use Plus Jakarta Sans throughout for a friendly but mature voice. Headlines are compact and confident. Body copy is spacious and plain. Avoid all caps except tiny debug-only build labels. User-facing copy must not expose terms such as heartbeat, credential, policy version, token, or backend.

# Layout

Design for portrait Android phones from 360 to 430 dp wide, with 390 by 844 dp as the primary canvas. Use 20 dp horizontal margins, an 8 dp rhythm, generous bottom safe-area padding, and one visually dominant action per screen. Forms and setup screens may scroll on smaller displays and with large font settings.

Avoid persistent bottom navigation during onboarding. Use a quiet top app bar with role/context and Back where navigation is reversible. Keep irreversible or security-sensitive actions explicit.

# Components

Use the accessible Composables UI component model for buttons, text fields, selectors, progress, dialogs, and status feedback. Primary buttons are full-width on onboarding screens, at least 52 dp tall, with 16 dp corners. Secondary actions are tonal or text buttons. Destructive actions are never styled as the default.

Text fields use filled warm surfaces, persistent labels, inline validation, and no placeholder-only labels. Month and year are separate selectors; never request an exact birth date. Child Profile avatars are generated locally from the first initial and a deterministic soft color.

Status cards have a clear title, plain-language description, small icon, and next action. Required capability rows show `Ready`, `Needs attention`, or `Checking` in both words and symbols.

# Motion

The Stitch baseline screens are static. Do not generate custom animation experiments, animated assets, ambient loops, SVG motion, Three.js, WebGL, canvas animation, bouncing, pulsing logos, or typewriter effects. Android may later use brief standard Compose transitions for navigation and state changes, always respecting the system reduced-motion preference. Never animate the QR pattern, countdown digits with layout shifts, validation errors, revoked-device screens, or critical failures.

# Illustration

Prefer typography and layout over illustration. The Child handoff and brand surfaces use no SVG, generated illustration, photo, character art, or pictorial logo. Never turn decorative artwork into a brand mark or repeat it beside the `Cereveil` wordmark.

# Accessibility and trust

Target WCAG AA contrast, 48 dp minimum touch targets, TalkBack labels, logical focus order, and layouts that survive 200 percent font scaling. Do not hide required explanations behind tooltips. Explain what each sensitive permission enables and what Cereveil does not collect.

The hackathon Protection Setup requests Accessibility, Usage Access, background location, microphone, permission to display Cereveil notifications, and battery-optimization exemption. It does not request Notification Listener or VPN access. The Child Device may not scan an Enrollment Code until all required hackathon capabilities are ready.

# Theme constraints

The hackathon is light-theme only. Do not use Android dynamic color. Keep tokens semantic so a dark theme can be added later. This phase targets separate fixed-role Guardian and Child APKs and must not show a role-selection screen.
