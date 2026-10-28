import { PetEngine, PetOptions } from './engine/engine';

/**
 * The pet on the color clock, built on the Pixel Pet engine (MIT; see engine/LICENSE). The display's own elements are its terrain: the clock and
 * label are text it climbs over (letters dip under its feet), and the reminder pill is a
 * stone to nap on.
 *
 * The clock's schedule is its day: awake and about during the day; in the last few
 * minutes before night it gets sleepy and heads to bed in a bottom corner; at night it
 * sleeps and pays no attention to touches; in the morning it wakes and does push-ups.
 */

export type Period = 'day' | 'dusk' | 'night';

export interface Scene {
  /** The day and night background colors (#rrggbb), and the one showing now. */
  dayColor: string;
  nightColor: string;
  background: string;
  /** Text color on the current background, for its bugs and footprints. */
  ink: string;
  period: Period;
}

export interface ClockPet {
  update(scene: Scene): void;
  destroy(): void;
}

/** Gecko colors to choose from, most natural first (hue, degrees). */
const HUES = [42, 120, 185, 215, 265, 320];
/** How far its hue must be from both backgrounds to stand out. */
const MIN_HUE_GAP = 60;
/** Bugs turn up by themselves this often during the day (seconds). */
const FLY_EVERY: [number, number] = [150, 420];
/** A tap away from the pet calls a fly over, at most this often (ms). */
const TAP_FLY_COOLDOWN = 8_000;

export function startClockPet(win: Window, first: Scene): ClockPet {
  const doc = win.document;
  const short = Math.min(win.innerWidth, win.innerHeight);
  const opts: PetOptions = {
    // Big enough to see from across a room on a phone, not huge on a monitor.
    px: Math.max(2, Math.min(6, Math.round(short / 120))),
    color: seedColor(first.dayColor, first.nightColor),
    nibble: false,
    // The digits are far too big to bounce under a gecko's feet.
    jostle: false,
    sleepAfterMs: Infinity,
    reducedMotion: win.matchMedia('(prefers-reduced-motion: reduce)').matches,
    maxFps: 30,
    critters: { fly: { enabled: true, pace: 'normal' }, cricket: { enabled: false, pace: 'normal' } },
    mischief: false,
    // ?petdebug in the address shows its terrain, route and home.
    debug: new URLSearchParams(win.location.search).has('petdebug'),
  };

  let scene = first;
  applyColors(doc, scene);
  const engine = new PetEngine(win, opts);
  engine.start();
  setPeriod(scene.period, true);

  let flyTimer = 0;
  const scheduleFly = () => {
    win.clearTimeout(flyTimer);
    flyTimer = win.setTimeout(() => {
      if (scene.period === 'day') engine.spawnBug('fly', randomSpot());
      scheduleFly();
    }, (FLY_EVERY[0] + Math.random() * (FLY_EVERY[1] - FLY_EVERY[0])) * 1000);
  };
  scheduleFly();

  // A quick tap somewhere away from the pet during the day brings a fly over to it.
  let down: { x: number; y: number; t: number } | null = null;
  let lastTapFly = 0;
  const onDown = (e: PointerEvent) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; };
  const onUp = (e: PointerEvent) => {
    const d = down;
    down = null;
    if (!d || scene.period !== 'day' || performance.now() - d.t > 350 || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 14) return;
    if (engine.hits(e.clientX, e.clientY, true) || performance.now() - lastTapFly < TAP_FLY_COOLDOWN) return;
    lastTapFly = performance.now();
    engine.spawnBug('fly', { x: e.clientX / opts.px, y: e.clientY / opts.px });
  };
  win.addEventListener('pointerdown', onDown, { passive: true });
  win.addEventListener('pointerup', onUp, { passive: true });

  function randomSpot() {
    return { x: (0.2 + Math.random() * 0.6) * win.innerWidth / opts.px, y: (0.25 + Math.random() * 0.5) * win.innerHeight / opts.px };
  }

  function setPeriod(period: Period, initial = false) {
    if (period === 'day') {
      engine.setOptions({ sleepAfterMs: Infinity, ignorePointer: false });
      if (!initial) {
        // Good morning: up, a blink, and a few proud push-ups.
        engine.command('wake');
        win.setTimeout(() => { if (scene.period === 'day') engine.command('pushups'); }, 2500);
      }
    } else {
      // "Away" to the pet means: go home to bed and stay asleep. It's sent all the way
      // there (or to nap on the reminder pill), not just to wherever counts as near home.
      engine.setOptions({ sleepAfterMs: 0, ignorePointer: period === 'night' });
      if (!engine.asleep) engine.command('sleep');
    }
  }

  return {
    update(next: Scene) {
      const colorsChanged = next.dayColor !== scene.dayColor || next.nightColor !== scene.nightColor || next.background !== scene.background || next.ink !== scene.ink;
      const periodChanged = next.period !== scene.period;
      scene = next;
      if (colorsChanged) {
        applyColors(doc, next);
        engine.setOptions({ color: seedColor(next.dayColor, next.nightColor) });
        engine.refreshPalette();
      }
      if (periodChanged) setPeriod(next.period);
    },
    destroy() {
      win.clearTimeout(flyTimer);
      win.removeEventListener('pointerdown', onDown);
      win.removeEventListener('pointerup', onUp);
      engine.destroy();
    },
  };
}

/** The engine reads the theme from these variables: the ground color and the text color. */
function applyColors(doc: Document, scene: Scene) {
  doc.documentElement.style.setProperty('--background-primary', scene.background);
  doc.documentElement.style.setProperty('--text-normal', scene.ink);
}

/** A gecko color that stands out against both the day and the night background. */
function seedColor(day: string, night: string) {
  const hues = [hueOf(day), hueOf(night)].filter((h): h is number => h !== null);
  const gap = (h: number) => Math.min(...hues.map((b) => Math.min(Math.abs(h - b), 360 - Math.abs(h - b))), 180);
  const pick = HUES.find((h) => gap(h) >= MIN_HUE_GAP) ?? HUES.reduce((a, b) => (gap(b) > gap(a) ? b : a));
  return `hsl(${pick} 70% 55%)`;
}

/** Hue of a #rrggbb color, or null for greys (black, white) which any gecko stands out on. */
function hueOf(hex: string): number | null {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (d < 0.12) return null;
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return h * 60;
}
