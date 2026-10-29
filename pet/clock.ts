import { PlayBall } from './ball';
import { BugKind } from './engine/bugs';
import { PetEngine, PetOptions } from './engine/engine';
import { ShadowStyle } from './engine/softshadow';
import { Traits, rollTraits } from './engine/personality';

/**
 * The pet on the color clock, built on the Pixel Pet engine (MIT; see engine/LICENSE).
 * The display's own elements are its terrain: the clock and label are solid in the shape
 * of their letters, and the reminder pill is a rounded stone to climb onto and nap on.
 *
 * The clock's schedule is its day: awake and about during the day, hunting the flies and
 * crickets that turn up and now and then playing with a ball; in the last few minutes
 * before night it gets sleepy and heads to bed; at night it sleeps, darkened so it doesn't
 * catch the eye, and pays no attention to touches; in the morning it wakes and does push-ups.
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
/** How far a hue must be from the backgrounds to stand out. */
const MIN_HUE_GAP = 60;
/** How bright the gecko is at night: dark enough not to catch the eye. */
const NIGHT_DIM = 0.38;
/** The change from day to night (and back) takes this long, matching the display's fade (ms). */
const PERIOD_FADE = 15_000;
/**
 * Bugs turn up by themselves during the day, about this often (seconds, at random between
 * the two), so it stays fed: a starving gecko is frantic in everything it does.
 */
const ON_THEIR_OWN: Record<BugKind, [number, number]> = { fly: [90, 240], cricket: [180, 420] };
/** A tap away from the pet calls a bug over, at most this often (ms). */
const TAP_BUG_COOLDOWN = 6_000;
/** A ball turns up about this often during the day, and lasts about this long (seconds). */
const BALL_EVERY: [number, number] = [360, 900];
const BALL_LASTS: [number, number] = [60, 180];
/** Its personality, rolled once and kept, like the original's settings do. */
const TRAITS_KEY = 'color-clock-pet-traits';

const between = ([lo, hi]: [number, number]) => (lo + Math.random() * (hi - lo)) * 1000;

export function startClockPet(win: Window, first: Scene): ClockPet {
  const doc = win.document;
  const short = Math.min(win.innerWidth, win.innerHeight);
  const px = Math.max(2, Math.min(6, Math.round(short / 120)));
  const ball = new PlayBall(win, px);
  const opts: PetOptions = {
    // Big enough to see from across a room on a phone, not huge on a monitor.
    px,
    color: seedColor(first.dayColor, first.nightColor),
    dim: first.period === 'night' ? NIGHT_DIM : 1,
    traits: loadTraits(win),
    nibble: false,
    // The original crisp pixel shadow, with a soft glow falling off around it.
    shadowStyle: 'halo',
    // The digits are far too big to bounce under a gecko's feet.
    jostle: false,
    sleepAfterMs: Infinity,
    reducedMotion: win.matchMedia('(prefers-reduced-motion: reduce)').matches,
    // The display rate: its strike is a burst of a tenth of a second, which at 30 fps is
    // only three or four frames and looks like a jump. (The phone is always on a charger.)
    maxFps: 0,
    critters: { fly: { enabled: true, pace: 'normal' }, cricket: { enabled: true, pace: 'normal' } },
    mischief: false,
    graph: ball,
    // ?petdebug in the address shows its terrain, route and home.
    debug: new URLSearchParams(win.location.search).has('petdebug'),
  };

  let scene = first;
  let dim = opts.dim ?? 1;
  let dimTimer = 0;
  applyColors(doc, scene);
  const engine = new PetEngine(win, opts);
  engine.start();

  const timers = new Set<number>();
  const later = (ms: number, fn: () => void) => {
    const id = win.setTimeout(() => { timers.delete(id); fn(); }, ms);
    timers.add(id);
  };

  // Bugs of their own accord, each kind at its own loose rhythm.
  for (const kind of Object.keys(ON_THEIR_OWN) as BugKind[]) {
    const next = () => later(between(ON_THEIR_OWN[kind]), () => {
      if (scene.period === 'day') engine.spawnBug(kind, randomSpot());
      next();
    });
    next();
  }

  // Now and then a ball rolls in; after a while it pops and play is over.
  let ballPop = 0;
  const nextBall = (first = false) => later(between(first ? [120, 360] : BALL_EVERY), () => {
    if (scene.period === 'day' && !ball.active) {
      const [color, shade] = ballColors(scene.dayColor, opts.color);
      ball.spawn(color, shade);
      later(1200, () => { if (ball.active) engine.command('play'); });
      ballPop = win.setTimeout(() => ball.pop(), between(BALL_LASTS));
    }
    nextBall();
  });
  nextBall(true);
  if (opts.debug) {
    // Test hooks: start ball play or bring a bug on demand.
    (win as unknown as { pet: object }).pet = {
      ball: () => { const [c, s] = ballColors(scene.dayColor, opts.color); ball.spawn(c, s); later(1200, () => engine.command('play')); },
      pop: () => ball.pop(),
      bug: (kind: BugKind) => engine.spawnBug(kind, randomSpot()),
      state: () => engine.state,
      shadow: (style: ShadowStyle) => engine.setOptions({ shadowStyle: style }),
      /** Show or hide the debug drawing (terrain, route) while keeping these hooks. */
      overlay: (on: boolean) => engine.setOptions({ debug: on }),
      /** Put a bug `ahead` art px in front of its head, for measuring strikes. */
      bait: (kind: BugKind, ahead: number) => {
        const b = (engine as unknown as { body: { head: { x: number; y: number }; heading: number } }).body;
        engine.spawnBug(kind, { x: b.head.x + Math.cos(b.heading) * ahead, y: b.head.y + Math.sin(b.heading) * ahead });
      },
      /** Every animation frame for `seconds`: time, state, hunt phase and head (CSS px). */
      trace: (seconds: number) => new Promise((resolve) => {
        const e = engine as unknown as { body: { head: { x: number; y: number } }; brain: { stateData: { phase?: string } } };
        const out: { t: number; s: string; p?: string; x: number; y: number }[] = [];
        const t0 = performance.now();
        const step = () => {
          const t = performance.now() - t0;
          out.push({ t: Math.round(t), s: engine.state, p: e.brain.stateData.phase, x: Math.round(e.body.head.x * px), y: Math.round(e.body.head.y * px) });
          if (t < seconds * 1000) win.requestAnimationFrame(step); else resolve(out);
        };
        win.requestAnimationFrame(step);
      }),
    };
  }

  // A quick tap somewhere away from the pet during the day brings a bug over to it.
  let down: { x: number; y: number; t: number } | null = null;
  let lastTapBug = 0;
  const onDown = (e: PointerEvent) => { down = { x: e.clientX, y: e.clientY, t: performance.now() }; };
  const onUp = (e: PointerEvent) => {
    const d = down;
    down = null;
    if (!d || scene.period !== 'day' || performance.now() - d.t > 350 || Math.hypot(e.clientX - d.x, e.clientY - d.y) > 14) return;
    if (engine.hits(e.clientX, e.clientY, true) || performance.now() - lastTapBug < TAP_BUG_COOLDOWN) return;
    lastTapBug = performance.now();
    engine.spawnBug(Math.random() < 0.7 ? 'fly' : 'cricket', { x: e.clientX / px, y: e.clientY / px });
  };
  const onResize = () => ball.resize();
  win.addEventListener('pointerdown', onDown, { passive: true });
  win.addEventListener('pointerup', onUp, { passive: true });
  win.addEventListener('resize', onResize);

  function randomSpot() {
    return { x: (0.2 + Math.random() * 0.6) * win.innerWidth / px, y: (0.25 + Math.random() * 0.5) * win.innerHeight / px };
  }

  /** Eases the gecko's colors toward `to` over `ms`, in step with the display's fade. */
  function fadeDim(to: number, ms: number) {
    win.clearInterval(dimTimer);
    if (ms <= 0) { dim = to; engine.setOptions({ dim }); return; }
    const from = dim, start = performance.now();
    dimTimer = win.setInterval(() => {
      const t = Math.min(1, (performance.now() - start) / ms);
      dim = from + (to - from) * (t < 0.5 ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t));
      engine.setOptions({ dim });
      if (t >= 1) win.clearInterval(dimTimer);
    }, 120);
  }

  function setPeriod(period: Period, initial = false) {
    fadeDim(period === 'night' ? NIGHT_DIM : 1, initial ? 0 : PERIOD_FADE);
    if (period === 'day') {
      engine.setOptions({ sleepAfterMs: Infinity, ignorePointer: false });
      if (!initial) {
        // Good morning: it wakes as the sunrise finishes, then a few proud push-ups.
        later(PERIOD_FADE * 0.6, () => { if (scene.period === 'day') engine.command('wake'); });
        later(PERIOD_FADE * 0.6 + 2500, () => { if (scene.period === 'day') engine.command('pushups'); });
      }
    } else {
      // Play's over for the day.
      win.clearTimeout(ballPop);
      ball.pop();
      // The bugs go too, so it isn't still out hunting them after dark.
      engine.shooBugs();
      // "Away" to the pet means: go home to bed and stay asleep. It's sent all the way
      // there (or to nap on the reminder pill), not just to wherever counts as near home.
      engine.setOptions({ sleepAfterMs: 0, ignorePointer: period === 'night' });
      if (!engine.asleep) engine.command('sleep');
    }
  }

  // Only once everything above exists (setPeriod touches the timers and the ball).
  try {
    setPeriod(scene.period, true);
  } catch (error) {
    // Never leave a half-started gecko behind.
    engine.destroy();
    throw error;
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
      for (const id of timers) win.clearTimeout(id);
      win.clearInterval(dimTimer);
      win.clearTimeout(ballPop);
      win.removeEventListener('pointerdown', onDown);
      win.removeEventListener('pointerup', onUp);
      win.removeEventListener('resize', onResize);
      ball.remove();
      engine.destroy();
    },
  };
}

/** This gecko's personality: rolled the first time, then the same every time it starts. */
function loadTraits(win: Window): Traits {
  try {
    const saved = JSON.parse(win.localStorage.getItem(TRAITS_KEY) ?? 'null');
    if (saved && typeof saved.speed === 'number') return saved;
  } catch { /* storage unavailable: a fresh one each time */ }
  const traits = rollTraits();
  try { win.localStorage.setItem(TRAITS_KEY, JSON.stringify(traits)); } catch { /* as above */ }
  return traits;
}

/** The engine reads the theme from these variables: the ground color and the text color. */
function applyColors(doc: Document, scene: Scene) {
  doc.documentElement.style.setProperty('--background-primary', scene.background);
  doc.documentElement.style.setProperty('--text-normal', scene.ink);
}

/** Of HUES, the first far enough from all of `avoid` (or the farthest one). */
function standOut(avoid: (number | null)[]) {
  const hues = avoid.filter((h): h is number => h !== null);
  const gap = (h: number) => Math.min(...hues.map((b) => Math.min(Math.abs(h - b), 360 - Math.abs(h - b))), 180);
  return HUES.find((h) => gap(h) >= MIN_HUE_GAP) ?? HUES.reduce((a, b) => (gap(b) > gap(a) ? b : a));
}

/** A gecko color that stands out against both the day and the night background. */
function seedColor(day: string, night: string) {
  return `hsl(${standOut([hueOf(day), hueOf(night)])} 70% 55%)`;
}

/** A ball that stands out from the daytime background and from the gecko. */
function ballColors(day: string, gecko: string): [string, string] {
  const geckoHue = Number(gecko.match(/hsl\((\d+)/)?.[1] ?? 0);
  const h = standOut([hueOf(day), geckoHue]);
  return [`hsl(${h} 80% 60%)`, `hsl(${h} 70% 42%)`];
}

/** Hue of a #rrggbb color, or null for greys (black, white) which any color stands out on. */
function hueOf(hex: string): number | null {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  if (d < 0.12) return null;
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return h * 60;
}
