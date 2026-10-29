import { nextChange, periodAt } from "/schedule.js";

const defaults = { dayStart: "07:00", nightStart: "19:00", dayColor: "#16a34a", nightColor: "#dc2626", showStatus: false, showTime: false, dayLabel: "", nightLabel: "", pet: true };
const palette = ["#16a34a", "#22c55e", "#0ea5e9", "#2563eb", "#7c3aed", "#db2777", "#dc2626", "#ea580c", "#f59e0b", "#facc15", "#ffffff", "#64748b", "#1e293b", "#000000"];
const drift = [[0, 0], [6, -4], [-5, 5], [4, 7], [-7, -3], [3, -7], [-3, 4], [7, 2]];
const storageKey = "color-clock-settings-v1";
/** The gecko gets sleepy and heads to bed this long before night (ms). */
const windDown = 10 * 60_000;
/** How long the change from day to night (and back) takes (ms). Settings edits stay quick. */
const periodFade = 15_000;
/** Inside the Android app, which owns settings and pushes them in. */
const embedded = new URLSearchParams(location.search).has("embed");
const $ = (selector) => document.querySelector(selector);
const sheet = $("#settings");
const overlay = $("#overlay");
let settings = loadSettings();
let draft = null;
let editing = "day";
let wakeLock = null;
let wantsWakeLock = false;
let overlayTimer;
let toastTimer;
let pet = null;
let petLoading = false;
let lastPeriod = null;
let fadeTimer;
let labelTimer;
let labelFading = false;

function loadSettings() {
  if (embedded) return { ...defaults };
  try { return { ...defaults, ...JSON.parse(localStorage.getItem(storageKey) || "{}") }; }
  catch { return { ...defaults }; }
}

const timeFormat = new Intl.DateTimeFormat([], { hour: "numeric", minute: "2-digit" });
function formatTime(value) {
  const [hour, minute] = value.split(":").map(Number);
  return timeFormat.format(new Date(2000, 0, 1, hour, minute));
}

function isLight(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.6;
}

// --- display ---

function render() {
  const now = new Date();
  const shown = draft || settings;
  const actual = periodAt(now, shown.dayStart, shown.nightStart);
  const period = draft ? editing : actual;
  const color = shown[`${period}Color`];
  // The real change from day to night (or back) is a slow sunset or sunrise; anything else,
  // like editing a color, moves at the usual quick pace.
  const turning = !draft && lastPeriod !== null && lastPeriod !== period;
  lastPeriod = draft ? lastPeriod : period;
  if (turning) {
    document.documentElement.style.setProperty("--fade", `${periodFade}ms`);
    clearTimeout(fadeTimer);
    fadeTimer = setTimeout(() => document.documentElement.style.removeProperty("--fade"), periodFade + 200);
  }
  document.body.style.backgroundColor = color;
  document.body.dataset.ink = isLight(color) ? "dark" : "light";
  $('meta[name="theme-color"]').content = color;

  const clock = $("#clock");
  clock.hidden = !shown.showTime;
  clock.dateTime = now.toISOString();
  clock.textContent = timeFormat.format(now);
  showLabel(shown[`${period}Label`], turning);

  const status = $("#status");
  status.hidden = !shown.showStatus;
  status.textContent = `${period === "day" ? "Day" : "Night"} · changes ${new Intl.DateTimeFormat([], { weekday: "short", hour: "numeric", minute: "2-digit" }).format(nextChange(now, actual, shown.dayStart, shown.nightStart))}`;

  const [x, y] = drift[now.getMinutes() % drift.length];
  document.documentElement.style.setProperty("--drift-x", `${x}px`);
  document.documentElement.style.setProperty("--drift-y", `${y}px`);

  // The pet keeps the real time of day, even while a different period is being previewed.
  const dusk = actual === "day" && nextChange(now, "day", shown.dayStart, shown.nightStart) - now <= windDown;
  const realColor = shown[`${actual}Color`];
  updatePet(shown, { dayColor: shown.dayColor, nightColor: shown.nightColor, background: color, ink: isLight(realColor) ? "#111111" : "#f8fafc", period: actual === "night" ? "night" : dusk ? "dusk" : "day" });
}

/** Sets the label; when the period turns, the old words fade out and the new ones fade in. */
function showLabel(text, turning) {
  const label = $("#label");
  if (turning && label.textContent !== text) {
    labelFading = true;
    label.style.transitionDuration = `${periodFade / 2}ms`;
    label.style.opacity = "0";
    clearTimeout(labelTimer);
    labelTimer = setTimeout(() => {
      label.textContent = text;
      label.hidden = !text;
      label.style.opacity = "1";
      labelTimer = setTimeout(() => { labelFading = false; label.style.transitionDuration = ""; }, periodFade / 2);
    }, periodFade / 2);
    return;
  }
  if (labelFading) return;
  label.textContent = text;
  label.hidden = !text;
}

/** Starts, updates or removes the gecko; its code only loads once it's switched on. */
function updatePet(shown, scene) {
  if (!shown.pet) {
    pet?.destroy();
    pet = null;
    return;
  }
  if (pet) { pet.update(scene); return; }
  if (petLoading) return;
  petLoading = true;
  import("/pet.js")
    .then(({ startClockPet }) => { if ((draft || settings).pet && !pet) pet = startClockPet(window, scene); })
    .catch((error) => console.error("Couldn't load the pet", error))
    .finally(() => { petLoading = false; });
}

function revealOverlay() {
  if (!sheet.hidden) return;
  overlay.classList.add("visible");
  clearTimeout(overlayTimer);
  overlayTimer = setTimeout(() => overlay.classList.remove("visible"), 4_000);
}

function toast(text) {
  const element = $("#toast");
  element.textContent = text;
  element.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { element.hidden = true; }, 2_600);
}

// --- settings sheet ---

function openSheet() {
  draft = { ...settings };
  editing = periodAt(new Date(), settings.dayStart, settings.nightStart);
  overlay.classList.remove("visible");
  $("#scrim").hidden = false;
  sheet.hidden = false;
  coverScreen();
  $("#show-time").checked = draft.showTime;
  $("#show-status").checked = draft.showStatus;
  $("#show-pet").checked = draft.pet;
  selectPeriod(editing);
  $("#save-settings").focus();
}

function closeSheet() {
  draft = null;
  sheet.hidden = true;
  $("#scrim").hidden = true;
  document.documentElement.style.setProperty("--covered-right", "0px");
  document.documentElement.style.setProperty("--covered-bottom", "0px");
  render();
}

/** Keeps the live preview centered in the part of the screen the sheet does not cover. */
function coverScreen() {
  if (sheet.hidden) return;
  // offsetLeft/offsetTop ignore the slide-in transform, so this is the sheet's resting place.
  const sideways = sheet.offsetLeft > window.innerWidth * 0.2;
  document.documentElement.style.setProperty("--covered-right", sideways ? `${window.innerWidth - sheet.offsetLeft}px` : "0px");
  document.documentElement.style.setProperty("--covered-bottom", sideways ? "0px" : `${window.innerHeight - sheet.offsetTop}px`);
}

function selectPeriod(period) {
  editing = period;
  $("#start").value = draft[`${period}Start`];
  $("#color").value = draft[`${period}Color`];
  $("#label-input").value = draft[`${period}Label`];
  $("#label-input").placeholder = period === "day" ? "e.g. OK to wake" : "e.g. Stay in bed";
  $("#editing-hint").textContent = `Editing ${period} · previewing on screen`;
  refreshSheet();
}

function refreshSheet() {
  for (const card of document.querySelectorAll(".period")) {
    const period = card.dataset.period;
    const color = draft[`${period}Color`];
    card.setAttribute("aria-selected", String(period === editing));
    card.style.setProperty("--period-color", color);
    card.style.setProperty("--card-ink", isLight(color) ? "0 0 0" : "255 255 255");
    card.querySelector(".period-time").textContent = formatTime(draft[`${period}Start`]);
    const label = card.querySelector(".period-label");
    label.textContent = draft[`${period}Label`] || "No label";
    label.classList.toggle("empty", !draft[`${period}Label`]);
  }
  const current = draft[`${editing}Color`];
  for (const swatch of $("#swatches").children) swatch.setAttribute("aria-pressed", String(swatch.dataset.color === current));
  render();
}

function updateDraft(key, value) {
  draft[`${editing}${key}`] = value;
  refreshSheet();
}

function saveSheet() {
  settings = { ...draft };
  try { localStorage.setItem(storageKey, JSON.stringify(settings)); toast("Saved"); }
  catch { toast("This browser couldn't save settings"); }
  closeSheet();
}

for (const color of palette) {
  const swatch = document.createElement("button");
  swatch.type = "button";
  swatch.dataset.color = color;
  swatch.style.setProperty("--swatch", color);
  swatch.setAttribute("aria-label", color);
  swatch.addEventListener("click", () => { $("#color").value = color; updateDraft("Color", color); });
  $("#swatches").append(swatch);
}

for (const card of document.querySelectorAll(".period")) card.addEventListener("click", () => selectPeriod(card.dataset.period));
$("#start").addEventListener("change", (event) => { if (event.target.value) updateDraft("Start", event.target.value); });
$("#color").addEventListener("input", (event) => updateDraft("Color", event.target.value.toLowerCase()));
$("#label-input").addEventListener("input", (event) => updateDraft("Label", event.target.value.trim()));
$("#show-time").addEventListener("change", (event) => { draft.showTime = event.target.checked; render(); });
$("#show-status").addEventListener("change", (event) => { draft.showStatus = event.target.checked; render(); });
$("#show-pet").addEventListener("change", (event) => { draft.pet = event.target.checked; render(); });
$("#open-settings").addEventListener("click", openSheet);
$("#cancel-settings").addEventListener("click", closeSheet);
$("#scrim").addEventListener("click", closeSheet);
$("#save-settings").addEventListener("click", saveSheet);
$("#screen").addEventListener("pointermove", revealOverlay);
$("#screen").addEventListener("click", (event) => { if (!event.target.closest(".sheet, .overlay, .scrim")) revealOverlay(); });
window.addEventListener("resize", coverScreen);
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !sheet.hidden && !document.fullscreenElement) closeSheet();
});

// --- this browser ---

$("#fullscreen").addEventListener("click", async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen();
  } catch { toast("This browser did not allow fullscreen"); }
});

async function requestWakeLock() {
  if (!("wakeLock" in navigator)) { toast("Screen wake lock is not supported here"); return; }
  try {
    wakeLock = await navigator.wakeLock.request("screen");
    wantsWakeLock = true;
    toast("Screen will stay awake while this page is visible");
    wakeLock.addEventListener("release", () => { wakeLock = null; });
  } catch { toast("The browser or device denied the wake lock"); }
}
$("#wake-lock").addEventListener("click", requestWakeLock);
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible" && wantsWakeLock && !wakeLock) requestWakeLock(); });

if (embedded) {
  document.body.classList.add("embedded");
  // The app calls this with its settings whenever they change.
  window.colorClock = { apply(next) { settings = { ...defaults, ...next }; render(); } };
}
render();
setInterval(render, 1_000);
if (!embedded && "serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");
