export function parseMinutes(value) {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) throw new TypeError(`Invalid time: ${value}`);
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
}

export function periodAt(date, dayStart, nightStart) {
  const now = date.getHours() * 60 + date.getMinutes();
  const day = parseMinutes(dayStart);
  const night = parseMinutes(nightStart);
  if (day === night) return "day";
  return day < night ? (now >= day && now < night ? "day" : "night") : (now >= day || now < night ? "day" : "night");
}

export function nextChange(date, period, dayStart, nightStart) {
  const target = parseMinutes(period === "day" ? nightStart : dayStart);
  const next = new Date(date);
  next.setHours(Math.floor(target / 60), target % 60, 0, 0);
  if (next <= date) next.setDate(next.getDate() + 1);
  return next;
}
