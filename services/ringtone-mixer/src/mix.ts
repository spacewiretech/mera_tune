import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { ffmpeg, inputArgsFor, measureLevels, probe, secs } from "./audio";
import { isBudgetStop, type RenderBudget } from "./budget";
import { MixError } from "./errors";
import { clamp, type MixRequest } from "./request";

/** Local inputs for one render. `workDir` must exist, be private to this render and is owned by the caller. */
export interface MixSources {
  bedPath: string;
  workDir: string;
}

export interface MixResult {
  mp3: Buffer;
  /** Decoded length of the MP3 in ms (exact sample count / 44100). */
  durationMs: number;
  /** Length of the trimmed name clip before any tempo change, in ms. */
  ttsDurationMs: number;
  /** atempo factor applied to the name clip; 1 when it fit as-is. */
  tempo: number;
}

export const MIN_TTS_SEC = 0.15;
export const MAX_TTS_SEC = 6;
export const DUCK_RAMP_SEC = 0.12;
export const TAIL_FADE_SEC = 1.2;
export const NAME_FADE_IN_SEC = 0.012;
export const NAME_FADE_OUT_SEC = 0.03;
export const TTS_GAIN_CLAMP_DB = 18;
/** Used when the bed's slot window is digitally silent and has no RMS to match. */
export const FALLBACK_BED_RMS_DB = -20;
export const OUTPUT_SAMPLE_RATE = 44_100;
export const LIMITER_CEILING = 0.95;

/** Step A: trim leading/trailing silence (keeping 30 ms), resample to 44.1 kHz mono. */
export const TRIM_FILTER = [
  "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.03",
  "areverse",
  "silenceremove=start_periods=1:start_threshold=-40dB:start_silence=0.03",
  "areverse",
  "aformat=sample_rates=44100:channel_layouts=mono",
].join(",");

export interface FitInputs {
  ttsLenSec: number;
  bedLenSec: number;
  slotStartMs: number;
  slotEndMs: number;
  maxTempo: number;
  maxDurationMs: number;
  /** Overall RMS (dBFS) of the bed inside the slot window; may be non-finite. */
  bedRmsDb: number;
  /** Overall RMS (dBFS) of the trimmed name clip; must be finite. */
  ttsRmsDb: number;
  slotGainDb: number;
}

export interface FitPlan {
  ttsLenSec: number;
  bedLenSec: number;
  slotStartSec: number;
  slotEndSec: number;
  slotLenSec: number;
  /** Rendered length: min(bed, max_duration). */
  outLenSec: number;
  tempo: number;
  /** Name clip length after atempo. */
  fitLenSec: number;
  /** Where the name clip starts in the output (centred in the slot). */
  offsetSec: number;
  ttsGainDb: number;
  /** True when the bed was longer than the output and a tail fade is needed. */
  bedCut: boolean;
}

export function outputLengthSec(bedLenSec: number, maxDurationMs: number): number {
  return Math.min(bedLenSec, maxDurationMs / 1000);
}

export function assertSlotWithinOutput(slotEndMs: number, outLenSec: number): void {
  const slotEndSec = slotEndMs / 1000;
  if (slotEndSec > outLenSec + 0.05) {
    throw new MixError(
      "BAD_REQUEST",
      `name slot ends at ${secs(slotEndSec)} s but the rendered bed is only ${secs(outLenSec)} s long`,
    );
  }
}

/** Step C: pure fit maths. Throws NAME_TOO_LONG when the clip needs more than max_tempo. */
export function planFit(input: FitInputs): FitPlan {
  const slotStartSec = input.slotStartMs / 1000;
  const slotEndSec = input.slotEndMs / 1000;
  const slotLenSec = slotEndSec - slotStartSec;
  const outLenSec = outputLengthSec(input.bedLenSec, input.maxDurationMs);
  assertSlotWithinOutput(input.slotEndMs, outLenSec);

  const rawTempo = Math.max(1, input.ttsLenSec / slotLenSec);
  if (rawTempo > input.maxTempo + 1e-6) {
    throw new MixError(
      "NAME_TOO_LONG",
      `name clip is ${input.ttsLenSec.toFixed(2)} s but the slot is ${slotLenSec.toFixed(2)} s; ` +
        `needs tempo ${rawTempo.toFixed(2)} > max ${input.maxTempo}`,
    );
  }
  const tempo = rawTempo > 1.0001 ? Number(rawTempo.toFixed(4)) : 1;
  const fitLenSec = input.ttsLenSec / tempo;
  const offsetSec = slotStartSec + (slotLenSec - fitLenSec) / 2;
  const bedRmsDb = Number.isFinite(input.bedRmsDb) ? input.bedRmsDb : FALLBACK_BED_RMS_DB;
  const ttsGainDb = clamp(bedRmsDb - input.ttsRmsDb + input.slotGainDb, -TTS_GAIN_CLAMP_DB, TTS_GAIN_CLAMP_DB);

  return {
    ttsLenSec: input.ttsLenSec,
    bedLenSec: input.bedLenSec,
    slotStartSec,
    slotEndSec,
    slotLenSec,
    outLenSec,
    tempo,
    fitLenSec,
    offsetSec,
    ttsGainDb,
    bedCut: input.bedLenSec > outLenSec + 0.01,
  };
}

/**
 * Bed gain over time: 1 outside the name, `duck_db` under it, linear 120 ms
 * ramps either side. All time constants are non-negative so the expression
 * never contains a unary minus.
 */
export function duckExpression(plan: FitPlan, duckDb: number): string {
  const g = Math.pow(10, duckDb / 20).toFixed(5);
  const nameStart = plan.offsetSec;
  const nameEnd = plan.offsetSec + plan.fitLenSec;
  const rampInStart = Math.max(0, nameStart - DUCK_RAMP_SEC);
  const rampInLen = nameStart - rampInStart;
  const rampOutEnd = nameEnd + DUCK_RAMP_SEC;
  const hold =
    `if(lt(t,${secs(nameEnd)}),${g},` +
    `if(lt(t,${secs(rampOutEnd)}),${g}+(1-${g})*(t-${secs(nameEnd)})/${secs(DUCK_RAMP_SEC)},1))`;
  if (rampInLen < 0.001) {
    return `if(lt(t,${secs(nameStart)}),1,${hold})`;
  }
  return (
    `if(lt(t,${secs(rampInStart)}),1,` +
    `if(lt(t,${secs(nameStart)}),1+(${g}-1)*(t-${secs(rampInStart)})/${secs(rampInLen)},${hold}))`
  );
}

/** Step D filter graph: bed (stereo, trimmed, ducked) + name (fitted, faded, gained, placed) -> amix -> limiter. */
/**
 * Mono -> stereo by duplicating the channel. A plain `aformat=...stereo` upmix
 * goes through swresample's rematrix, which feeds a mono source into each side
 * at -3 dB and would make the name (or a mono bed) quieter than the level match
 * computed on the mono signal.
 */
const MONO_TO_STEREO = ["aformat=sample_rates=44100:channel_layouts=mono", "pan=stereo|c0=c0|c1=c0"];
const TO_STEREO = ["aformat=sample_rates=44100:channel_layouts=stereo"];

export function buildFilterGraph(plan: FitPlan, duckDb: number, bedChannels = 2): string {
  const out = secs(plan.outLenSec);

  const bedChain: string[] = [
    ...(bedChannels === 1 ? MONO_TO_STEREO : TO_STEREO),
    `atrim=end=${out}`,
    "asetpts=PTS-STARTPTS",
  ];
  if (duckDb < 0) {
    // 256-sample frames make the per-frame volume ramps ~6 ms steps instead of ~23 ms.
    bedChain.push("asetnsamples=n=256:p=0", `volume=volume='${duckExpression(plan, duckDb)}':eval=frame`);
  }

  const nameChain: string[] = [];
  if (plan.tempo !== 1) nameChain.push(`atempo=${plan.tempo.toFixed(4)}`);
  const delayMs = Math.max(0, Math.round(plan.offsetSec * 1000));
  nameChain.push(
    `afade=t=in:st=0:d=${secs(NAME_FADE_IN_SEC)}`,
    // Exact tail fade regardless of atempo's rounding: fade-in on the reversed clip.
    "areverse",
    `afade=t=in:st=0:d=${secs(NAME_FADE_OUT_SEC)}`,
    "areverse",
    `volume=${plan.ttsGainDb.toFixed(2)}dB`,
    ...MONO_TO_STEREO,
    `adelay=${delayMs}|${delayMs}`,
    `apad=whole_dur=${out}`,
    `atrim=end=${out}`,
  );

  const mixChain: string[] = ["amix=inputs=2:duration=first:normalize=0"];
  if (plan.bedCut) {
    const fade = Math.min(TAIL_FADE_SEC, plan.outLenSec / 2);
    mixChain.push(`afade=t=out:st=${secs(plan.outLenSec - fade)}:d=${secs(fade)}`);
  }
  // level=0: do not scale peaks back up to 0 dBFS after limiting.
  mixChain.push(`alimiter=limit=${LIMITER_CEILING}:level=0`);

  return [
    `[0:a]${bedChain.join(",")}[bed]`,
    `[1:a]${nameChain.join(",")}[name]`,
    `[bed][name]${mixChain.join(",")}[out]`,
  ].join(";");
}

/**
 * Mixes the TTS name clip into the bed. Pure with respect to the request: it
 * reads `src.bedPath`, writes scratch files under `src.workDir` and returns the
 * MP3 bytes. The caller removes `workDir`. Every ffmpeg/ffprobe call runs under
 * `budget` (the server always passes one): it fails with DEADLINE_EXCEEDED once
 * the time is spent and CLIENT_CLOSED (child killed) when the client disconnects.
 */
export async function mix(req: MixRequest, src: MixSources, budget?: RenderBudget): Promise<MixResult> {
  const ttsSrcPath = path.join(src.workDir, req.tts.input.kind === "raw" ? "tts_input.pcm" : "tts_input.bin");
  const ttsTrimPath = path.join(src.workDir, "tts_trim.wav");
  const outPath = path.join(src.workDir, "out.mp3");
  await writeFile(ttsSrcPath, req.tts.pcm);

  // A. PCM -> trimmed 44.1 kHz mono WAV.
  await ffmpeg(
    [...inputArgsFor(req.tts.input), "-i", ttsSrcPath, "-af", TRIM_FILTER, "-c:a", "pcm_s16le", "-f", "wav", ttsTrimPath],
    { budget },
  );

  // B. Durations and RMS level match.
  const ttsInfo = await probe(ttsTrimPath, budget);
  const ttsLenSec = ttsInfo.durationSec;
  if (ttsLenSec < MIN_TTS_SEC) {
    throw new MixError(
      "TTS_EMPTY",
      `name clip is ${Math.round(ttsLenSec * 1000)} ms after trimming silence; minimum is ${MIN_TTS_SEC * 1000} ms`,
    );
  }
  if (ttsLenSec > MAX_TTS_SEC) {
    throw new MixError("TTS_TOO_LONG", `name clip is ${ttsLenSec.toFixed(2)} s after trimming; maximum is ${MAX_TTS_SEC} s`);
  }

  let bedLenSec: number;
  let bedChannels = 2;
  try {
    const bedInfo = await probe(src.bedPath, budget);
    bedLenSec = bedInfo.durationSec;
    bedChannels = bedInfo.channels ?? 2;
  } catch (err) {
    if (isBudgetStop(err)) throw err;
    throw new MixError("BED_DOWNLOAD_FAILED", "bed is not decodable audio", err instanceof MixError ? err.detail : String(err));
  }
  if (bedLenSec <= 0) throw new MixError("BED_DOWNLOAD_FAILED", "bed has no measurable duration");
  assertSlotWithinOutput(req.slotEndMs, outputLengthSec(bedLenSec, req.maxDurationMs));

  const ttsStats = await measureLevels(ttsTrimPath, undefined, { budget });
  if (!Number.isFinite(ttsStats.rmsDb)) throw new MixError("TTS_EMPTY", "name clip has no measurable level");
  const bedStats = await measureLevels(
    src.bedPath,
    { startSec: req.slotStartMs / 1000, endSec: req.slotEndMs / 1000 },
    { budget },
  );

  // C. Fit.
  const plan = planFit({
    ttsLenSec,
    bedLenSec,
    slotStartMs: req.slotStartMs,
    slotEndMs: req.slotEndMs,
    maxTempo: req.maxTempo,
    maxDurationMs: req.maxDurationMs,
    bedRmsDb: bedStats.rmsDb,
    ttsRmsDb: ttsStats.rmsDb,
    slotGainDb: req.slotGainDb,
  });

  // D. One render.
  await ffmpeg(
    [
      "-i",
      src.bedPath,
      "-i",
      ttsTrimPath,
      "-filter_complex",
      buildFilterGraph(plan, req.duckDb, bedChannels),
      "-map",
      "[out]",
      "-c:a",
      "libmp3lame",
      "-b:a",
      "160k",
      "-ar",
      String(OUTPUT_SAMPLE_RATE),
      "-ac",
      "2",
      "-metadata",
      `title=${req.title}`,
      "-metadata",
      "artist=MeraTune",
      "-f",
      "mp3",
      outPath,
    ],
    { budget },
  );

  const mp3 = await readFile(outPath);
  if (mp3.length === 0) throw new MixError("FFMPEG_FAILED", "ffmpeg produced an empty file");

  // Exact decoded length; falls back to the container duration if astats gave nothing.
  const outStats = await measureLevels(outPath, undefined, { budget });
  const durationMs =
    outStats.samples > 0
      ? Math.round((outStats.samples / OUTPUT_SAMPLE_RATE) * 1000)
      : Math.round((await probe(outPath, budget)).durationSec * 1000);

  return {
    mp3,
    durationMs,
    ttsDurationMs: Math.round(ttsLenSec * 1000),
    tempo: plan.tempo,
  };
}
