/**
 * Golden test for the mixer pipeline. Runs inside the container:
 *   node dist/test/golden.js
 *
 * Synthesises a 12 s stereo sine bed at -20 dBFS RMS with a name slot at
 * 4000-6500 ms and tone-burst "names" as s16le PCM, calls mix() directly and
 * asserts on the MP3 with ffprobe/astats. Exit code 0 only when every check passes.
 * The budget checks also assert that deadline expiry and client disconnect kill
 * the running ffmpeg (looked up in /proc, so they need Linux, i.e. the container).
 */
import { mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { FFMPEG_BIN, ffmpeg, measureLevels, probe, runTool, tailOf } from "../src/audio";
import { DEFAULT_DEADLINE_MS, RenderBudget, parseDeadlineMs } from "../src/budget";
import { MixError, httpStatusFor } from "../src/errors";
import { mix, planFit, type MixResult } from "../src/mix";
import { describeTtsInput, parseMixRequest, type MixRequest } from "../src/request";

const BED_SEC = 12;
const BED_RMS_DB = -20;
const SLOT_START_MS = 4000;
const SLOT_END_MS = 6500;
const TTS_RATE = 24_000;
/** Parsed and validated only; the golden test never touches the network. */
const BED_URL = "https://lltfhcsmojzoxpewjrfk.supabase.co/storage/v1/object/sign/tune-beds/golden.wav?token=golden";
/**
 * With the plan's defaults (slot_gain 3, duck -6) a level-matched name only
 * lifts the slot-window RMS by ~3 dB, so the ">6 dB rise" assertion uses a
 * 9 dB slot gain. Everything else stays at production defaults.
 */
const SLOT_GAIN_DB = 9;

interface Check {
  name: string;
  ok: boolean;
  detail: string;
}
const checks: Check[] = [];

function check(name: string, ok: boolean, detail: string): void {
  checks.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}  [${detail}]`);
}

function near(actual: number, expected: number, tolerance: number): boolean {
  return Number.isFinite(actual) && Math.abs(actual - expected) <= tolerance;
}

function db(value: number): string {
  return `${value.toFixed(2)} dB`;
}

/** s16le mono tone burst: `leadSec` silence, `toneSec` of 600 Hz at 0.3 FS with 10 ms fades, `tailSec` silence. */
function toneBurstPcm(toneSec: number, leadSec = 0, tailSec = 0): Buffer {
  const amplitude = 0.3;
  const freq = 600;
  const fadeSamples = Math.round(0.01 * TTS_RATE);
  const total = Math.round((leadSec + toneSec + tailSec) * TTS_RATE);
  const buf = Buffer.alloc(total * 2);
  const toneStart = Math.round(leadSec * TTS_RATE);
  const toneEnd = Math.min(total, toneStart + Math.round(toneSec * TTS_RATE));
  for (let i = toneStart; i < toneEnd; i++) {
    const env = Math.min(1, (i - toneStart) / fadeSamples, (toneEnd - 1 - i) / fadeSamples);
    const sample = amplitude * env * Math.sin((2 * Math.PI * freq * i) / TTS_RATE);
    buf.writeInt16LE(Math.round(sample * 32767), i * 2);
  }
  return buf;
}

function silentPcm(seconds: number): Buffer {
  return Buffer.alloc(Math.round(seconds * TTS_RATE) * 2);
}

let requestCounter = 0;
function requestFor(pcm: Buffer, overrides: Record<string, unknown> = {}): MixRequest {
  requestCounter += 1;
  return parseMixRequest({
    render_id: `golden-${requestCounter}`,
    bed_url: BED_URL,
    tts: { pcm_base64: pcm.toString("base64"), mime: "audio/L16;codec=pcm;rate=24000", sample_rate: TTS_RATE },
    slot_start_ms: SLOT_START_MS,
    slot_end_ms: SLOT_END_MS,
    slot_gain_db: SLOT_GAIN_DB,
    duck_db: -6,
    max_duration_ms: 30_000,
    title: "Golden Test",
    ...overrides,
  });
}

async function synthBed(dir: string, channels: 1 | 2 = 2): Promise<string> {
  const bedPath = path.join(dir, channels === 1 ? "bed-mono.wav" : "bed.wav");
  // RMS of a sine with amplitude A is A/sqrt(2); -20 dBFS RMS -> A = 0.1 * sqrt(2).
  const amplitude = Math.pow(10, BED_RMS_DB / 20) * Math.SQRT2;
  const expr = `${amplitude.toFixed(6)}*sin(2*PI*440*t)`;
  await ffmpeg([
    "-f",
    "lavfi",
    "-i",
    channels === 1
      ? `aevalsrc=exprs=${expr}:channel_layout=mono:sample_rate=44100:duration=${BED_SEC}`
      : `aevalsrc=exprs=${expr}|${expr}:channel_layout=stereo:sample_rate=44100:duration=${BED_SEC}`,
    "-c:a",
    "pcm_s16le",
    bedPath,
  ]);
  return bedPath;
}

async function runMix(
  dir: string,
  label: string,
  req: MixRequest,
  bedPath: string,
  budget?: RenderBudget,
): Promise<{ result: MixResult; outPath: string }> {
  const workDir = await mkdtemp(path.join(dir, `${label}-`));
  const result = await mix(req, { bedPath, workDir }, budget);
  const outPath = path.join(dir, `${label}.mp3`);
  await writeFile(outPath, result.mp3);
  return { result, outPath };
}

async function expectMixError(name: string, code: string, run: () => Promise<MixResult>): Promise<void> {
  try {
    const result = await run();
    check(name, false, `expected ${code} but mix succeeded with tempo ${result.tempo}`);
  } catch (err) {
    if (err instanceof MixError) {
      check(name, err.code === code, `code=${err.code} http=${err.httpStatus} message="${err.message}"`);
    } else {
      check(name, false, `unexpected error: ${err instanceof Error ? err.stack ?? err.message : String(err)}`);
    }
  }
}

/** Like expectMixError for any async step; also fails when the error arrives later than `maxMs`. */
async function expectCode(name: string, code: string, run: () => Promise<unknown>, maxMs = Number.POSITIVE_INFINITY): Promise<void> {
  const started = Date.now();
  try {
    await run();
    check(name, false, `expected ${code} but it succeeded`);
  } catch (err) {
    const ms = Date.now() - started;
    if (err instanceof MixError) {
      check(name, err.code === code && ms <= maxMs, `code=${err.code} http=${err.httpStatus} after ${ms} ms (limit ${maxMs}) message="${err.message}"`);
    } else {
      check(name, false, `unexpected error: ${err instanceof Error ? err.stack ?? err.message : String(err)}`);
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Live processes whose argv contains `marker` (zombies have an empty cmdline and do not count). */
async function processesWithArg(marker: string): Promise<number> {
  let count = 0;
  for (const entry of await readdir("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const cmdline = await readFile(`/proc/${entry}/cmdline`, "latin1");
      if (cmdline.split("\0").some((arg) => arg.includes(marker))) count += 1;
    } catch {
      // Process exited while scanning.
    }
  }
  return count;
}

async function waitUntilGone(marker: string, timeoutMs = 2_000): Promise<number> {
  const until = Date.now() + timeoutMs;
  let count = await processesWithArg(marker);
  while (count > 0 && Date.now() < until) {
    await sleep(50);
    count = await processesWithArg(marker);
  }
  return count;
}

/** ffmpeg argv that runs ~20 s (`-re` paces a silent source in real time) unless killed; `marker` tags it in /proc. */
function slowFfmpegArgs(marker: string): string[] {
  return [
    "-hide_banner", "-nostdin", "-nostats", "-loglevel", "error",
    "-re", "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "20",
    "-metadata", `comment=${marker}`, "-f", "null", "-",
  ];
}

function budgetUnitChecks(): void {
  check(
    "MIX_DEADLINE_MS unset -> 55000 ms default; DEADLINE_EXCEEDED is HTTP 504",
    parseDeadlineMs(undefined) === 55_000 && DEFAULT_DEADLINE_MS === 55_000 && httpStatusFor("DEADLINE_EXCEEDED") === 504,
    `default=${String(parseDeadlineMs(undefined))} http=${httpStatusFor("DEADLINE_EXCEEDED")}`,
  );
  check(
    "MIX_DEADLINE_MS '3000' -> 3000; 'abc', '500', '2.5' -> invalid",
    parseDeadlineMs("3000") === 3000 &&
      parseDeadlineMs("abc") === undefined &&
      parseDeadlineMs("500") === undefined &&
      parseDeadlineMs("2.5") === undefined,
    `3000=${String(parseDeadlineMs("3000"))} abc=${String(parseDeadlineMs("abc"))}`,
  );
  const now = Date.now();
  const fresh = new RenderBudget(55_000, undefined, now).timeoutFor(40_000, "unit");
  check("budget 55 s: step timeout = 40 s cap, not budget-limited", fresh.timeoutMs === 40_000 && !fresh.limited, JSON.stringify(fresh));
  const late = new RenderBudget(55_000, undefined, now - 45_000).timeoutFor(40_000, "unit");
  check(
    "budget with ~10 s left: step timeout = time left, budget-limited",
    late.limited && late.timeoutMs > 9_000 && late.timeoutMs <= 10_000,
    JSON.stringify(late),
  );
}

async function budgetChecks(dir: string, bedPath: string): Promise<void> {
  budgetUnitChecks();
  const pcm = toneBurstPcm(1.8, 0.05, 0.05);

  await expectCode("budget: spent before a step -> DEADLINE_EXCEEDED without spawning", "DEADLINE_EXCEEDED", async () =>
    new RenderBudget(1_000, undefined, Date.now() - 2_000).timeoutFor(40_000, "unit"),
  );
  const alreadyGone = new AbortController();
  alreadyGone.abort();
  await expectCode("budget: client already gone -> CLIENT_CLOSED without spawning", "CLIENT_CLOSED", async () =>
    new RenderBudget(55_000, alreadyGone.signal).timeoutFor(40_000, "unit"),
  );

  // runTool: the budget, not the 40 s cap, kills a long ffmpeg.
  const deadlineMarker = `golden-deadline-${process.pid}-${Date.now()}`;
  await expectCode(
    "runTool: 400 ms budget kills a 20 s ffmpeg -> DEADLINE_EXCEEDED",
    "DEADLINE_EXCEEDED",
    () => runTool(FFMPEG_BIN, slowFfmpegArgs(deadlineMarker), { budget: new RenderBudget(400) }),
    2_500,
  );
  const leftAfterDeadline = await waitUntilGone(deadlineMarker);
  check("runTool: ffmpeg is gone after the deadline kill", leftAfterDeadline === 0, `${leftAfterDeadline} left`);

  // The per-call cap still applies (and stays FFMPEG_FAILED) when the budget has more time.
  const capMarker = `golden-cap-${process.pid}-${Date.now()}`;
  await expectCode(
    "runTool: 300 ms cap under a 30 s budget -> FFMPEG_FAILED timeout, not DEADLINE_EXCEEDED",
    "FFMPEG_FAILED",
    () => runTool(FFMPEG_BIN, slowFfmpegArgs(capMarker), { timeoutMs: 300, budget: new RenderBudget(30_000) }),
    2_500,
  );
  await waitUntilGone(capMarker);

  // runTool: client disconnect kills the running child.
  const closeMarker = `golden-close-${process.pid}-${Date.now()}`;
  const client = new AbortController();
  const running = runTool(FFMPEG_BIN, slowFfmpegArgs(closeMarker), { budget: new RenderBudget(30_000, client.signal) });
  running.catch(() => undefined); // asserted below; avoid an unhandled rejection while we look at /proc
  await sleep(400);
  const beforeAbort = await processesWithArg(closeMarker);
  check("runTool: slow ffmpeg is visible in /proc before the disconnect", beforeAbort === 1, `${beforeAbort} running`);
  client.abort();
  await expectCode("runTool: client disconnect -> CLIENT_CLOSED", "CLIENT_CLOSED", () => running, 2_000);
  const leftAfterClose = await waitUntilGone(closeMarker);
  check("runTool: ffmpeg is killed on client disconnect", leftAfterClose === 0, `${leftAfterClose} left`);

  // mix(): the same stops through the whole pipeline.
  await expectCode("mix(): budget already spent -> DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED", () =>
    runMix(dir, "budget-spent", requestFor(pcm), bedPath, new RenderBudget(1_000, undefined, Date.now() - 2_000)),
  );
  await expectCode(
    "mix(): 150 ms deadline expires mid-render -> DEADLINE_EXCEEDED",
    "DEADLINE_EXCEEDED",
    () => runMix(dir, "budget-tiny", requestFor(pcm), bedPath, new RenderBudget(150)),
    2_500,
  );
  const midRender = new AbortController();
  setTimeout(() => midRender.abort(), 40);
  await expectCode(
    "mix(): client disconnect mid-render -> CLIENT_CLOSED",
    "CLIENT_CLOSED",
    () => runMix(dir, "budget-closed", requestFor(pcm), bedPath, new RenderBudget(55_000, midRender.signal)),
    2_500,
  );
  const ok = await runMix(dir, "budget-ok", requestFor(pcm), bedPath, new RenderBudget(DEFAULT_DEADLINE_MS, new AbortController().signal));
  check("mix(): default 55 s budget with a live client succeeds", near(ok.result.durationMs, BED_SEC * 1000, 50), `${ok.result.durationMs} ms`);
}

function unitChecks(): void {
  const banner = [
    "Input #0, mp3, from 'out.mp3':",
    "  Metadata:",
    "    title           : Jai Shri Ram Ayush ji..",
    "    artist          : MeraTune",
    "    encoder         : Lavf59.27.100",
    "  Duration: 00:00:12.04, start: 0.025057, bitrate: 160 kb/s",
    "[Parsed_astats_0 @ 0x1] RMS level dB: -12.45",
  ].join("\n");
  const redacted = tailOf(banner);
  check(
    "tailOf strips ffmpeg metadata (personalized title never logged)",
    !redacted.includes("Ayush") && !redacted.includes("title") && redacted.includes("Duration") && redacted.includes("RMS level"),
    JSON.stringify(redacted),
  );

  const gemini = describeTtsInput("audio/L16;codec=pcm;rate=24000");
  check(
    "mime audio/L16;codec=pcm;rate=24000 -> s16le mono 24000",
    gemini.kind === "raw" && gemini.format === "s16le" && gemini.sampleRate === 24_000 && gemini.channels === 1,
    JSON.stringify(gemini),
  );
  const wav = describeTtsInput("audio/wav");
  check("mime audio/wav -> container input", wav.kind === "container", JSON.stringify(wav));
  const override = describeTtsInput("audio/pcm", 16_000);
  check(
    "mime audio/pcm with sample_rate 16000 -> s16le 16000",
    override.kind === "raw" && override.format === "s16le" && override.sampleRate === 16_000,
    JSON.stringify(override),
  );

  const plan = planFit({
    ttsLenSec: 2.9,
    bedLenSec: 12,
    slotStartMs: SLOT_START_MS,
    slotEndMs: SLOT_END_MS,
    maxTempo: 1.3,
    maxDurationMs: 30_000,
    bedRmsDb: -20,
    ttsRmsDb: -13.5,
    slotGainDb: 3,
  });
  check(
    "planFit: 2.9 s clip on 2.5 s slot -> tempo 1.16, centred, gain -3.5 dB",
    near(plan.tempo, 1.16, 0.0001) && near(plan.offsetSec, 4.0, 0.001) && near(plan.ttsGainDb, -3.5, 0.001),
    `tempo=${plan.tempo} offset=${plan.offsetSec.toFixed(3)} gain=${plan.ttsGainDb.toFixed(2)}`,
  );
  let cut: MixError | undefined;
  try {
    planFit({ ...planInputs(3.4), maxTempo: 1.3 });
  } catch (err) {
    cut = err instanceof MixError ? err : undefined;
  }
  check("planFit: 3.4 s clip on 2.5 s slot -> NAME_TOO_LONG", cut?.code === "NAME_TOO_LONG", cut?.message ?? "no error");
}

function planInputs(ttsLenSec: number) {
  return {
    ttsLenSec,
    bedLenSec: 12,
    slotStartMs: SLOT_START_MS,
    slotEndMs: SLOT_END_MS,
    maxTempo: 1.3,
    maxDurationMs: 30_000,
    bedRmsDb: -20,
    ttsRmsDb: -13.5,
    slotGainDb: 3,
  };
}

async function main(): Promise<void> {
  unitChecks();

  const dir = await mkdtemp(path.join(os.tmpdir(), "golden-"));
  try {
    const bedPath = await synthBed(dir);
    const bedInfo = await probe(bedPath);
    const bedSlot = await measureLevels(bedPath, { startSec: SLOT_START_MS / 1000, endSec: SLOT_END_MS / 1000 });
    const bedPre = await measureLevels(bedPath, { startSec: 0, endSec: 3.8 });
    const bedPost = await measureLevels(bedPath, { startSec: 6.7, endSec: BED_SEC });
    check("bed synthesised: 12 s at -20 dBFS RMS", near(bedInfo.durationSec, BED_SEC, 0.01) && near(bedSlot.rmsDb, BED_RMS_DB, 0.3),
      `duration=${bedInfo.durationSec.toFixed(3)} s slotRms=${db(bedSlot.rmsDb)}`);

    // Case 1: 1.8 s name with 50 ms lead/tail silence, fits without tempo change.
    const main = await runMix(dir, "case1", requestFor(toneBurstPcm(1.8, 0.05, 0.05)), bedPath);
    const info1 = await probe(main.outPath);
    const slot1 = await measureLevels(main.outPath, { startSec: SLOT_START_MS / 1000, endSec: SLOT_END_MS / 1000 });
    const pre1 = await measureLevels(main.outPath, { startSec: 0, endSec: 3.8 });
    const post1 = await measureLevels(main.outPath, { startSec: 6.7, endSec: BED_SEC });
    const all1 = await measureLevels(main.outPath);

    check("case1 X-Mix-Duration-Ms = bed ±50 ms", near(main.result.durationMs, BED_SEC * 1000, 50), `${main.result.durationMs} ms`);
    check("case1 ffprobe duration = bed ±50 ms", near(info1.durationSec * 1000, BED_SEC * 1000, 50), `${(info1.durationSec * 1000).toFixed(0)} ms`);
    check("case1 tts duration ≈ 1.86 s (1.8 s tone + 2×30 ms kept silence)", near(main.result.ttsDurationMs, 1860, 40), `${main.result.ttsDurationMs} ms`);
    check("case1 tempo = 1 when the clip fits", main.result.tempo === 1, `tempo=${main.result.tempo}`);
    check("case1 slot-window RMS rises > 6 dB vs bare bed", slot1.rmsDb - bedSlot.rmsDb > 6, `${db(bedSlot.rmsDb)} -> ${db(slot1.rmsDb)} (+${(slot1.rmsDb - bedSlot.rmsDb).toFixed(2)})`);
    check("case1 pre-slot RMS within ±1 dB of bed", near(pre1.rmsDb, bedPre.rmsDb, 1), `${db(bedPre.rmsDb)} -> ${db(pre1.rmsDb)}`);
    check("case1 post-slot RMS within ±1 dB of bed", near(post1.rmsDb, bedPost.rmsDb, 1), `${db(bedPost.rmsDb)} -> ${db(post1.rmsDb)}`);
    check("case1 peak < -0.5 dBFS", all1.peakDb < -0.5, `peak=${db(all1.peakDb)}`);
    check("case1 codec is mp3", info1.codecName === "mp3", `codec=${info1.codecName ?? "?"}`);
    check("case1 sample rate 44100", info1.sampleRate === 44_100, `rate=${info1.sampleRate ?? "?"}`);
    check("case1 size < 1.5 MB", main.result.mp3.length < 1.5 * 1024 * 1024, `${main.result.mp3.length} bytes`);
    check("case1 starts with an ID3 tag", main.result.mp3.subarray(0, 3).toString("latin1") === "ID3", main.result.mp3.subarray(0, 3).toString("hex"));

    // Case 2: 3.2 s clip on the 2.5 s slot needs tempo 1.28 -> rejected at max_tempo 1.2.
    await expectMixError("case2 3.2 s clip, max_tempo 1.2 -> NAME_TOO_LONG", "NAME_TOO_LONG", async () => {
      const { result } = await runMix(dir, "case2", requestFor(toneBurstPcm(3.2), { max_tempo: 1.2 }), bedPath);
      return result;
    });

    // Case 3: 3.4 s clip needs tempo 1.36 -> rejected at the default max_tempo 1.3.
    await expectMixError("case3 3.4 s clip, default max_tempo -> NAME_TOO_LONG", "NAME_TOO_LONG", async () => {
      const { result } = await runMix(dir, "case3", requestFor(toneBurstPcm(3.4)), bedPath);
      return result;
    });

    // Case 4: 2.9 s clip -> accepted with tempo ≈ 1.16, output still bed length.
    const fit = await runMix(dir, "case4", requestFor(toneBurstPcm(2.9), { max_tempo: 1.2 }), bedPath);
    const slot4 = await measureLevels(fit.outPath, { startSec: SLOT_START_MS / 1000, endSec: SLOT_END_MS / 1000 });
    const all4 = await measureLevels(fit.outPath);
    check("case4 2.9 s clip accepted with tempo ≈ 1.16", near(fit.result.tempo, 1.16, 0.02), `tempo=${fit.result.tempo} ttsMs=${fit.result.ttsDurationMs}`);
    check("case4 duration = bed ±50 ms", near(fit.result.durationMs, BED_SEC * 1000, 50), `${fit.result.durationMs} ms`);
    check("case4 slot-window RMS rises > 6 dB", slot4.rmsDb - bedSlot.rmsDb > 6, `+${(slot4.rmsDb - bedSlot.rmsDb).toFixed(2)} dB`);
    check("case4 peak < -0.5 dBFS", all4.peakDb < -0.5, `peak=${db(all4.peakDb)}`);

    // Case 5: digital silence -> TTS_EMPTY after trimming.
    await expectMixError("case5 silent clip -> TTS_EMPTY", "TTS_EMPTY", async () => {
      const { result } = await runMix(dir, "case5", requestFor(silentPcm(1.0)), bedPath);
      return result;
    });

    // Case 6: max_duration_ms 8000 cuts the 12 s bed -> 8 s output with a tail fade.
    const cut = await runMix(dir, "case6", requestFor(toneBurstPcm(1.8, 0.05, 0.05), { max_duration_ms: 8000 }), bedPath);
    const info6 = await probe(cut.outPath);
    const all6 = await measureLevels(cut.outPath);
    const tail6 = await measureLevels(cut.outPath, { startSec: 7.7, endSec: 8.0 });
    check("case6 max_duration 8000 -> duration 8000 ±50 ms", near(cut.result.durationMs, 8000, 50), `${cut.result.durationMs} ms (ffprobe ${(info6.durationSec * 1000).toFixed(0)} ms)`);
    check("case6 tail faded (last 300 ms RMS < bed - 6 dB)", tail6.rmsDb < BED_RMS_DB - 6, `tailRms=${db(tail6.rmsDb)}`);
    check("case6 peak < -0.5 dBFS", all6.peakDb < -0.5, `peak=${db(all6.peakDb)}`);

    // Case 7: mono bed. Upmix must duplicate the channel (no -3 dB rematrix loss),
    // so bed level and name lift match the stereo case.
    const monoBedPath = await synthBed(dir, 1);
    const mono = await runMix(dir, "case7", requestFor(toneBurstPcm(1.8, 0.05, 0.05)), monoBedPath);
    const pre7 = await measureLevels(mono.outPath, { startSec: 0, endSec: 3.8 });
    const slot7 = await measureLevels(mono.outPath, { startSec: SLOT_START_MS / 1000, endSec: SLOT_END_MS / 1000 });
    check("case7 mono bed: pre-slot RMS within ±1 dB of -20 dBFS", near(pre7.rmsDb, BED_RMS_DB, 1), `pre=${db(pre7.rmsDb)}`);
    check("case7 mono bed: slot lift matches stereo case ±1 dB", near(slot7.rmsDb, slot1.rmsDb, 1), `mono=${db(slot7.rmsDb)} stereo=${db(slot1.rmsDb)}`);

    // Case 8: per-request deadline and client disconnect.
    await budgetChecks(dir, bedPath);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }

  const failed = checks.filter((c) => !c.ok);
  console.log(`\ngolden: ${checks.length - failed.length} passed, ${failed.length} failed`);
  if (failed.length > 0) {
    for (const c of failed) console.log(`  FAIL ${c.name}: ${c.detail}`);
    process.exitCode = 1;
  }
}

main().catch((err: unknown) => {
  console.error("golden: crashed", err instanceof Error ? err.stack ?? err.message : err);
  if (err instanceof MixError && err.detail) console.error("detail:", err.detail);
  process.exit(1);
});
