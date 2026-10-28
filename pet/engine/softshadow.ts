/**
 * The gecko's shadow drawn on its own full-resolution layer under the pixel-art canvas.
 * The renderer still works out where every part of the shadow falls (offset by height,
 * lying on whatever it lands on); this only draws those shapes, in one of a few styles
 * between the original blocky pixels and a smooth, soft shadow.
 */
export interface ShadowShape { x: number; y: number; rx: number; ry: number; }

/**
 * - soft: smooth shapes with a soft edge.
 * - fine: crisp pixels half the size of the gecko's.
 * - softPixel: the gecko's own pixel grid, edges lightly blurred.
 * - halo: a faint soft glow, drawn around the original pixel shadow (which the renderer keeps).
 */
export type ShadowStyle = 'pixel' | 'soft' | 'fine' | 'softPixel' | 'halo';

interface Look { grid: number; blur: number; alpha: number; grow: number; }

export class SoftShadow {
  readonly canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private scratch: HTMLCanvasElement;
  private sctx: CanvasRenderingContext2D;
  private cells: HTMLCanvasElement;
  private cctx: CanvasRenderingContext2D;
  /** The area drawn last frame (device px), cleared before the next. */
  private dirty: { x: number; y: number; w: number; h: number } | null = null;

  constructor(private win: Window, public style: ShadowStyle) {
    const doc = win.document;
    this.canvas = doc.createElement('canvas');
    this.canvas.className = 'clock-pet-shadow';
    this.canvas.setAttribute('aria-hidden', 'true');
    this.ctx = this.canvas.getContext('2d')!;
    this.scratch = doc.createElement('canvas');
    this.sctx = this.scratch.getContext('2d')!;
    this.cells = doc.createElement('canvas');
    this.cctx = this.cells.getContext('2d', { willReadFrequently: true })!;
  }

  /** Whether the renderer should still paint its own pixel shadow. */
  get keepsPixels() { return this.style === 'pixel' || this.style === 'halo'; }

  resize() {
    const dpr = this.win.devicePixelRatio || 1;
    this.canvas.width = Math.round(this.win.innerWidth * dpr);
    this.canvas.height = Math.round(this.win.innerHeight * dpr);
    this.canvas.style.width = `${this.win.innerWidth}px`;
    this.canvas.style.height = `${this.win.innerHeight}px`;
    this.dirty = null;
  }

  remove() { this.canvas.remove(); }

  /** How each style is drawn, given the size of one art pixel in CSS px. */
  private look(art: number): Look | null {
    switch (this.style) {
      case 'soft': return { grid: 0, blur: art * 0.5, alpha: 1, grow: 1 };
      case 'fine': return { grid: art / 2, blur: 0, alpha: 1, grow: 1 };
      case 'softPixel': return { grid: art, blur: art * 0.3, alpha: 1, grow: 1 };
      case 'halo': return { grid: 0, blur: art * 1.4, alpha: 0.55, grow: 1.35 };
      default: return null;
    }
  }

  /**
   * Draw `shapes` (pixel-canvas px) in `color` (an rgba() string). `scale` is CSS px per
   * pixel-canvas px; `art` is CSS px per art pixel.
   */
  draw(shapes: ShadowShape[], color: string, scale: number, art: number) {
    const g = this.ctx, dpr = this.win.devicePixelRatio || 1, k = scale * dpr;
    if (this.dirty) g.clearRect(this.dirty.x, this.dirty.y, this.dirty.w, this.dirty.h);
    this.dirty = null;
    const look = this.look(art);
    if (!look || !shapes.length) return;

    const cell = look.grid * dpr;
    const pad = Math.ceil(look.blur * dpr * 3 + cell) + 2;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const s of shapes) {
      x0 = Math.min(x0, (s.x - s.rx * look.grow) * k); y0 = Math.min(y0, (s.y - s.ry * look.grow) * k);
      x1 = Math.max(x1, (s.x + s.rx * look.grow) * k); y1 = Math.max(y1, (s.y + s.ry * look.grow) * k);
    }
    // On a pixel grid, the box starts on a cell boundary so the cells line up with the gecko's.
    let bx = Math.floor(x0) - pad, by = Math.floor(y0) - pad;
    if (cell) { bx = Math.floor(bx / cell) * cell; by = Math.floor(by / cell) * cell; }
    const bw = Math.ceil(x1) + pad - bx, bh = Math.ceil(y1) + pad - by;
    if (bw <= 0 || bh <= 0 || bw > 4096 || bh > 4096) return;

    const m = color.match(/rgba?\(\s*([\d.]+)[ ,]+([\d.]+)[ ,]+([\d.]+)(?:[ ,/]+([\d.]+))?/);
    const rgb = m ? `rgb(${m[1]},${m[2]},${m[3]})` : color;
    const alpha = (m && m[4] !== undefined ? Number(m[4]) : 1) * look.alpha;

    if (this.scratch.width < bw || this.scratch.height < bh) {
      this.scratch.width = Math.max(this.scratch.width, bw);
      this.scratch.height = Math.max(this.scratch.height, bh);
    }
    const s = this.sctx;
    s.clearRect(0, 0, bw, bh);
    if (cell) {
      // Paint at one canvas pixel per cell, snap each to fully in or out, then scale up blocky.
      const cw = Math.ceil(bw / cell), ch = Math.ceil(bh / cell);
      this.cells.width = cw;
      this.cells.height = ch;
      const c = this.cctx;
      c.fillStyle = rgb;
      c.beginPath();
      for (const sh of shapes) {
        const cx = (sh.x * k - bx) / cell, cy = (sh.y * k - by) / cell;
        const rx = Math.max(0.5, (sh.rx * look.grow * k) / cell), ry = Math.max(0.5, (sh.ry * look.grow * k) / cell);
        c.moveTo(cx + rx, cy);
        c.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
      }
      c.fill();
      const img = c.getImageData(0, 0, cw, ch);
      for (let i = 3; i < img.data.length; i += 4) img.data[i] = img.data[i] >= 110 ? 255 : 0;
      c.putImageData(img, 0, 0);
      s.imageSmoothingEnabled = false;
      s.drawImage(this.cells, 0, 0, cw, ch, 0, 0, cw * cell, ch * cell);
    } else {
      s.fillStyle = rgb;
      s.beginPath();
      for (const sh of shapes) {
        const cx = sh.x * k - bx, cy = sh.y * k - by;
        const rx = Math.max(0.5, sh.rx * look.grow * k), ry = Math.max(0.5, sh.ry * look.grow * k);
        s.moveTo(cx + rx, cy);
        s.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
      }
      s.fill();
    }

    g.save();
    g.globalAlpha = alpha;
    if (look.blur) g.filter = `blur(${(look.blur * dpr).toFixed(1)}px)`;
    g.drawImage(this.scratch, 0, 0, bw, bh, bx, by, bw, bh);
    g.restore();
    this.dirty = { x: bx - pad, y: by - pad, w: bw + pad * 2, h: bh + pad * 2 };
  }
}
