// Builds the signed Android APK and publishes it as a GitHub release; phones install it on their
// next update check. Usage: node tools/android-release.mjs [--bump] [--build-only] [--notes "..."]
import { execFileSync, execSync } from "node:child_process";
import { copyFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const repo = "Giblicious/color-clock";
const root = join(import.meta.dirname, "..");
const androidDir = join(root, "android");
const versionFile = join(androidDir, "version.properties");
const outputDir = join(androidDir, "app", "build", "outputs", "apk", "release");
const args = process.argv.slice(2);

if (!existsSync(join(androidDir, "signing", "signing.properties"))) fail("android/signing/signing.properties is missing; releases must be signed with the original key.");

const version = Object.fromEntries(readFileSync(versionFile, "utf8").split(/\r?\n/).filter(Boolean).map((line) => line.split("=")));
if (args.includes("--bump")) {
  version.versionCode = String(Number(version.versionCode) + 1);
  const parts = version.versionName.split(".").map(Number);
  parts[2] = (parts[2] || 0) + 1;
  version.versionName = parts.join(".");
  writeFileSync(versionFile, `versionCode=${version.versionCode}\nversionName=${version.versionName}\n`);
}

console.log(`Building v${version.versionName} (${version.versionCode})…`);
const windows = process.platform === "win32";
const gradlew = join(androidDir, windows ? "gradlew.bat" : "gradlew");
const studioJdk = "C:/Program Files/Android/Android Studio/jbr";
const buildEnv = { ...process.env };
if (!buildEnv.JAVA_HOME && windows && existsSync(studioJdk)) buildEnv.JAVA_HOME = studioJdk;
const buildOptions = { cwd: androidDir, env: buildEnv, stdio: "inherit" };
if (windows) execSync(`"${gradlew}" assembleRelease --console=plain -q`, buildOptions);
else execFileSync(gradlew, ["assembleRelease", "--console=plain", "-q"], buildOptions);

// The app finds its update by this file name, so the version code must be in it.
const apk = join(outputDir, `color-clock-${version.versionCode}.apk`);
copyFileSync(join(outputDir, "app-release.apk"), apk);
console.log(`APK: ${apk}`);
if (args.includes("--build-only")) process.exit(0);

const notesIndex = args.indexOf("--notes");
const notes = notesIndex >= 0 ? args[notesIndex + 1] : `Color Clock ${version.versionName}`;
const tag = `v${version.versionName}`;
execFileSync("gh", ["release", "create", tag, apk, "--repo", repo, "--title", `Color Clock ${version.versionName}`, "--notes", notes], { stdio: "inherit" });
console.log(`Published ${tag}; phones install it on their next check (within 6 hours, or tap Check for update).`);

function fail(message) {
  console.error(message);
  process.exit(1);
}
