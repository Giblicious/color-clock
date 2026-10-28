import type { Ball, Playground } from './engine/personality';

/**
 * A ball for the gecko to play with, drawn in its pixel style. The engine sees it through
 * the same Playground interface as graph nodes in the original: it holds the ball where it
 * wants it (batting, carrying) and lets go, and the ball rolls on with the speed it was
 * given, slowing down and bouncing off the edges. When play time is up it pops.
 * Positions are CSS px.
 */
export class PlayBall implements Playground {
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private x = 0;
  private y = 0;
  private vx = 0;
  private vy = 0;
  private held: { x: number; y: number } | null = null;
  private alive = false;
  /** Seconds into popping, or -1 when not popping. */
  private popAt = -1;
  private bits: { x: number; y: number; vx: number; vy: number }[] = [];
  private raf = 0;
  private last = 0;
  private color = '#ffffff';
  private shade = '#cccccc';

  constructor(private win: Window, private px: number) {
    this.canvas = win.document.createElement('canvas');
    this.canvas.className = 'clock-pet-ball';
    this.canvas.setAttribute('aria-hidden', 'true');
    this.ctx = this.canvas.getContext('2d')!;
  }

  /** Radius in CSS px: a ball about the size of the gecko's head. */
  get r() { return 3.5 * this.px; }
  get active() { return this.alive && this.popAt < 0; }

  /** Rolls a ball in from a side edge toward the middle of the screen. */
  spawn(color: string, shade: string) {
    const w = this.win.innerWidth, h = this.win.innerHeight;
    const fromLeft = Math.random() < 0.5;
    this.color = color;
    this.shade = shade;
    this.x = fromLeft ? -this.r : w + this.r;
    this.y = h * (0.55 + Math.random() * 0.3);
    this.vx = (fromLeft ? 1 : -1) * (180 + Math.random() * 120);
    this.vy = -40 + Math.random() * 80;
    this.held = null;
    this.alive = true;
    this.popAt = -1;
    this.bits = [];
    // Under the gecko, so it's drawn over the ball when it carries or stands on it.
    if (!this.canvas.isConnected) this.win.document.body.insertBefore(this.canvas, this.win.document.querySelector('.clock-pet-overlay'));
    this.resize();
    this.last = performance.now();
    this.win.cancelAnimationFrame(this.raf);
    this.raf = this.win.requestAnimationFrame(this.tick);
  }

  /** Play is over: a pop and a few flying scraps. */
  pop() {
    if (!this.active) return;
    this.popAt = 0;
    this.held = null;
    for (let i = 0; i < 8; i++) {
      const a = (i / 8) * Math.PI * 2 + Math.random() * 0.5, v = 90 + Math.random() * 90;
      this.bits.push({ x: this.x, y: this.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v });
    }
  }

  remove() {
    this.alive = false;
    this.win.cancelAnimationFrame(this.raf);
    this.canvas.remove();
  }

  resize() {
    const dpr = this.win.devicePixelRatio || 1;
    this.canvas.width = Math.round(this.win.innerWidth * dpr);
    this.canvas.height = Math.round(this.win.innerHeight * dpr);
    this.canvas.style.width = `${this.win.innerWidth}px`;
    this.canvas.style.height = `${this.win.innerHeight}px`;
  }

  // --- Playground ---

  balls(): Ball[] {
    return this.active ? [{ id: 'ball', x: this.x, y: this.y, r: this.r }] : [];
  }

  push(_id: string, x: number, y: number) {
    if (this.active) this.held = { x, y };
  }

  letGo() {
    this.held = null;
  }

  // --- simulation ---

  private tick = (t: number) => {
    this.raf = this.win.requestAnimationFrame(this.tick);
    const dt = Math.min(0.05, Math.max(0, (t - this.last) / 1000));
    this.last = t;
    if (this.popAt >= 0) {
      this.popAt += dt;
      for (const b of this.bits) { b.x += b.vx * dt; b.y += b.vy * dt; b.vx *= 0.9; b.vy *= 0.9; }
      if (this.popAt > 0.5) { this.remove(); return; }
    } else if (this.held) {
      // Carried or batted: it goes where it's put, and keeps that speed when let go.
      const nx = this.held.x, ny = this.held.y;
      if (dt > 0) { this.vx = (nx - this.x) / dt; this.vy = (ny - this.y) / dt; }
      const cap = 600;
      const v = Math.hypot(this.vx, this.vy);
      if (v > cap) { this.vx *= cap / v; this.vy *= cap / v; }
      this.x = nx;
      this.y = ny;
    } else {
      this.x += this.vx * dt;
      this.y += this.vy * dt;
      const friction = Math.pow(0.35, dt);
      this.vx *= friction;
      this.vy *= friction;
      const r = this.r, w = this.win.innerWidth, h = this.win.innerHeight;
      if (this.x < r && this.vx < 0) { this.x = r; this.vx *= -0.7; }
      if (this.x > w - r && this.vx > 0) { this.x = w - r; this.vx *= -0.7; }
      if (this.y < r && this.vy < 0) { this.y = r; this.vy *= -0.7; }
      if (this.y > h - r && this.vy > 0) { this.y = h - r; this.vy *= -0.7; }
    }
    this.draw();
  };

  /** A chunky pixel ball: lit side, shaded side and a glint, on the gecko's pixel grid. */
  private draw() {
    const g = this.ctx, dpr = this.win.devicePixelRatio || 1, cell = this.px * dpr;
    g.clearRect(0, 0, this.canvas.width, this.canvas.height);
    if (this.popAt >= 0) {
      g.fillStyle = this.color;
      const size = Math.max(1, Math.round(cell * (1 - this.popAt * 1.6)));
      for (const b of this.bits) g.fillRect(Math.round(b.x * dpr), Math.round(b.y * dpr), size, size);
      return;
    }
    const n = 7, cx = this.x * dpr, cy = this.y * dpr, half = (n * cell) / 2;
    const ox = Math.round(cx - half), oy = Math.round(cy - half);
    // Shadow, then the ball, a cell at a time.
    g.fillStyle = 'rgba(0,0,0,0.18)';
    g.fillRect(ox + cell, oy + n * cell - cell * 0.4, (n - 2) * cell, cell);
    for (let j = 0; j < n; j++) {
      for (let i = 0; i < n; i++) {
        const dx = i - (n - 1) / 2, dy = j - (n - 1) / 2;
        if (dx * dx + dy * dy > (n / 2) * (n / 2) - 0.5) continue;
        g.fillStyle = dx + dy > 1.5 ? this.shade : this.color;
        g.fillRect(ox + i * cell, oy + j * cell, Math.ceil(cell), Math.ceil(cell));
      }
    }
    g.fillStyle = 'rgba(255,255,255,0.85)';
    g.fillRect(ox + 2 * cell, oy + 2 * cell, Math.ceil(cell), Math.ceil(cell));
  }
}
