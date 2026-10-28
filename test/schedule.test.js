import assert from "node:assert/strict";
import test from "node:test";
import { nextChange, parseMinutes, periodAt } from "../public/schedule.js";

const localDate = (hour, minute) => new Date(2026, 7, 10, hour, minute, 0, 0);

test("parses valid 24-hour times", () => {
  assert.equal(parseMinutes("07:30"), 450);
  assert.throws(() => parseMinutes("24:00"), /Invalid time/);
});

test("uses the default daytime window", () => {
  assert.equal(periodAt(localDate(7, 0), "07:00", "19:00"), "day");
  assert.equal(periodAt(localDate(18, 59), "07:00", "19:00"), "day");
  assert.equal(periodAt(localDate(19, 0), "07:00", "19:00"), "night");
  assert.equal(periodAt(localDate(2, 0), "07:00", "19:00"), "night");
});

test("supports a daytime period that crosses midnight", () => {
  assert.equal(periodAt(localDate(23, 0), "19:00", "07:00"), "day");
  assert.equal(periodAt(localDate(6, 59), "19:00", "07:00"), "day");
  assert.equal(periodAt(localDate(12, 0), "19:00", "07:00"), "night");
});

test("finds the next scheduled boundary", () => {
  assert.equal(nextChange(localDate(18, 0), "day", "07:00", "19:00").getHours(), 19);
  const tomorrow = nextChange(localDate(20, 0), "night", "07:00", "19:00");
  assert.equal(tomorrow.getDate(), 11);
  assert.equal(tomorrow.getHours(), 7);
});
