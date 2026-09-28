// deno test --allow-read supabase/functions/tests
import { assert, assertEquals, assertThrows } from "jsr:@std/assert@1";
import { ApiError } from "../generate-ringtone/errors.ts";
import { buildTitle, sanitizeName } from "../generate-ringtone/names.ts";
import { authenticateCaller, parseCredentials } from "../generate-ringtone/request.ts";
import type { ServiceClient } from "../_shared/supabase-client.ts";
import {
  clampLimit,
  type GenerationRow,
  isCurrentRender,
  MINE_MODE_MAX_ROWS,
  mineModeRows,
  NAME_MODE_MAX_ROWS,
  nameModeRows,
  normalizeRows,
  parseRequest,
  publicTune,
  readsOwnRows,
  type RenderRow,
  sampleMatchIds,
  toGenerationRow,
  toRenderRow,
  toTuneRow,
  type TuneRow,
  uniqueIds,
} from "../name-ringtones/rows.ts";

function tune(overrides: Partial<TuneRow> = {}): TuneRow {
  return {
    id: "t1",
    name: "Jai Shri Shyam",
    category_id: "c1",
    gender: "Male",
    language: "Hindi",
    tune_url: "https://cdn.example/stock/t1.mp3",
    is_active: true,
    is_personalizable: true,
    featured_rank: null,
    sample_name: "Shyam",
    title_template: "Jai Shri Ram {name} ji..",
    assets_version: 2,
    tts_voice_name: "Puck",
    likes_count: 10,
    views_count: 20,
    category: { id: "c1", name: "Bhakti", image_url: "" },
    ...overrides,
  };
}

function render(overrides: Partial<RenderRow> = {}): RenderRow {
  return {
    id: "r1",
    tune_id: "t1",
    assets_version: 2,
    tts_voice: "Puck",
    voice: "Male",
    language: "Hindi",
    public_url: "https://cdn.example/generated/t1/r1.mp3",
    created_at: "2026-09-20T10:00:00+00:00",
    ...overrides,
  };
}

function generation(overrides: Partial<GenerationRow> = {}): GenerationRow {
  return {
    id: "g1",
    tune_id: "t1",
    render_id: "r1",
    name_display: "Ayush",
    name_normalized: "ayush",
    language: "Hindi",
    title: "Jai Shri Ram Ayush ji..",
    created_at: "2026-09-21T10:00:00+00:00",
    ...overrides,
  };
}

function byId(...tunes: TuneRow[]): Map<string, TuneRow> {
  return new Map(tunes.map((t) => [t.id, t]));
}

function nameKey(raw: string): { display: string; normalized: string } {
  const result = sanitizeName(raw);
  if (!result.ok) throw new Error(`fixture name rejected: ${raw}`);
  return { display: result.display, normalized: result.normalized };
}

// ---------------------------------------------------------------------------------------------
// Request parsing
// ---------------------------------------------------------------------------------------------

Deno.test("parseRequest: name mode by default, capped at 20", () => {
  const request = parseRequest({ user_id: 7, user_token: "tok", name: "Ayush" });
  assertEquals(request.mode, "name");
  assertEquals(request.name, "Ayush");
  assertEquals(request.limit, NAME_MODE_MAX_ROWS);
  assertEquals(request.credentials, { userId: 7, userToken: "tok" });
});

Deno.test("parseRequest: mine mode, capped at 50, limit lowers the cap", () => {
  assertEquals(parseRequest({ user_token: "tok", mine: true }).limit, MINE_MODE_MAX_ROWS);
  assertEquals(parseRequest({ user_token: "tok", mine: true, limit: 5 }).limit, 5);
  assertEquals(parseRequest({ user_token: "tok", mine: true, limit: 500 }).limit, MINE_MODE_MAX_ROWS);
  assertEquals(parseRequest({ user_token: "tok", mine: false, name: "Ram" }).mode, "name");
});

Deno.test("parseRequest: credentials follow generate-ringtone's rules", () => {
  const status = (body: unknown) => {
    try {
      parseRequest(body);
      return null;
    } catch (err) {
      assert(err instanceof ApiError);
      return `${err.status} ${err.code}`;
    }
  };
  assertEquals(status(null), "400 INVALID_REQUEST");
  assertEquals(status([1]), "400 INVALID_REQUEST");
  assertEquals(status({ name: "Ram" }), "401 UNAUTHORIZED");
  assertEquals(status({ user_id: -3, name: "Ram" }), "400 INVALID_REQUEST");
  assertEquals(status({ user_id: "abc", name: "Ram" }), "400 INVALID_REQUEST");
  assertEquals(status({ user_token: 42, user_id: 1 }), "400 INVALID_REQUEST");
  assertEquals(status({ user_token: "tok", mine: "yes" }), "400 INVALID_REQUEST");
  assertEquals(status({ user_id: "12", name: "Ram" }), null);
  assertEquals(parseCredentials({ user_id: " 12 ", user_token: "  " }), { userId: 12, userToken: null });
});

Deno.test("readsOwnRows: a session token reads own rows in both modes", () => {
  assertEquals(readsOwnRows("mine", "token"), true);
  assertEquals(readsOwnRows("name", "token"), true);
});

Deno.test("readsOwnRows: a bare legacy user_id is 401 in mine mode and gets no own rows by name", () => {
  const err = assertThrows(() => readsOwnRows("mine", "legacy_user_id"), ApiError);
  assertEquals(err.status, 401);
  assertEquals(err.code, "UNAUTHORIZED");
  assertEquals(readsOwnRows("name", "legacy_user_id"), false);
});

Deno.test("legacy user_id with the flag on authenticates, but still cannot read another user's list", async () => {
  // The legacy branch never touches the client; a token would.
  const noClient = {} as unknown as ServiceClient;
  const config = { generate_allow_legacy_user_id: "true" };
  const request = parseRequest({ user_id: 42, mine: true });
  const caller = await authenticateCaller(noClient, config, request.credentials);
  assertEquals(caller, { userId: 42, authMode: "legacy_user_id" });
  assertThrows(() => readsOwnRows(request.mode, caller.authMode), ApiError, "log in");

  const byName = parseRequest({ user_id: 42, name: "Ayush" });
  const nameCaller = await authenticateCaller(noClient, config, byName.credentials);
  assertEquals(readsOwnRows(byName.mode, nameCaller.authMode), false);
});

Deno.test("nameModeRows without own generations carries no generation ids", () => {
  const rows = nameModeRows({
    display: "Ayush",
    sampleTuneIds: [],
    renders: [render()],
    tunesById: byId(tune({ sample_name: "Shyam" })),
    ownGenerations: [],
    limit: NAME_MODE_MAX_ROWS,
  });
  assertEquals(rows.length, 1);
  assertEquals(rows[0].generation_id, null);
});

Deno.test("clampLimit: missing, zero, negative or non-integer means the max", () => {
  for (const raw of [undefined, null, 0, -1, 2.5, "x", {}]) assertEquals(clampLimit(raw, 20), 20, String(raw));
  assertEquals(clampLimit("3", 20), 3);
  assertEquals(clampLimit(21, 20), 20);
});

// ---------------------------------------------------------------------------------------------
// Normalization reuse
// ---------------------------------------------------------------------------------------------

Deno.test("name key is generate-ringtone's sanitizeName key (case, spaces, emoji)", () => {
  assertEquals(nameKey("  AYUSH  ").normalized, "ayush");
  assertEquals(nameKey("Ayush 🙏").normalized, "ayush");
  assertEquals(nameKey("ram   kumar").display, "ram kumar");
  const blocked = sanitizeName("chutiya");
  assert(!blocked.ok && blocked.code === "NAME_REJECTED");
  const empty = sanitizeName(undefined);
  assert(!empty.ok && empty.code === "INVALID_NAME");
});

Deno.test("sampleMatchIds: authored sample names normalize like generate-ringtone's short-circuit", () => {
  const rows = [
    { id: "a", sample_name: " SHYAM " },
    { id: "b", sample_name: "Shyam🙏" },
    { id: "c", sample_name: "Shyamu" },
    { id: "d", sample_name: null },
    { id: "e", sample_name: "श्याम" },
  ];
  assertEquals(sampleMatchIds(rows, nameKey("shyam").normalized), ["a", "b"]);
  assertEquals(sampleMatchIds(rows, nameKey("श्याम").normalized), ["e"]);
  assertEquals(sampleMatchIds(rows, ""), []);
});

Deno.test("buildTitle: placeholder replaced everywhere, appended without one, null without a template", () => {
  assertEquals(buildTitle("Jai {name} ji, {name}!", "Ram"), "Jai Ram ji, Ram!");
  assertEquals(buildTitle("Happy birthday", "Ram"), "Happy birthday Ram");
  assertEquals(buildTitle("  ", "Ram"), null);
  assertEquals(buildTitle(null, "Ram"), null);
});

// ---------------------------------------------------------------------------------------------
// Row normalization
// ---------------------------------------------------------------------------------------------

Deno.test("toTuneRow: nulls become strings the app model accepts; category normalized", () => {
  const row = toTuneRow({
    id: "t9",
    name: null,
    category_id: null,
    gender: null,
    language: "Hindi",
    tune_url: "https://x/y.mp3",
    is_personalizable: true,
    featured_rank: "3",
    sample_name: "",
    title_template: null,
    assets_version: null,
    likes_count: null,
    category: [{ id: 5, name: null }],
  });
  assert(row);
  assertEquals(row.name, "");
  assertEquals(row.category_id, "");
  assertEquals(row.gender, "");
  assertEquals(row.is_active, true);
  assertEquals(row.featured_rank, 3);
  assertEquals(row.sample_name, null);
  assertEquals(row.assets_version, 1);
  assertEquals(row.likes_count, 0);
  assertEquals(row.category, { id: "5", name: "", image_url: "" });
  assertEquals(toTuneRow({ name: "no id" }), null);
});

Deno.test("publicTune drops the authoring voice", () => {
  const out = publicTune(tune());
  assert(!("tts_voice_name" in out));
  assertEquals(out.id, "t1");
});

Deno.test("toRenderRow / toGenerationRow drop malformed rows", () => {
  assertEquals(normalizeRows([render(), { id: "x", tune_id: "t1", public_url: " " }, null], toRenderRow).length, 1);
  assertEquals(normalizeRows("nope", toRenderRow), []);
  const g = toGenerationRow({ id: "g", tune_id: "t", render_id: null, title: " ", name_display: "A" });
  assertEquals(g?.render_id, null);
  assertEquals(g?.title, null);
  assertEquals(toGenerationRow({ tune_id: "t" }), null);
});

Deno.test("isCurrentRender: same assets_version and TTS voice as the tune today", () => {
  assert(isCurrentRender(render(), tune()));
  assert(!isCurrentRender(render({ assets_version: 1 }), tune()));
  assert(!isCurrentRender(render({ tts_voice: "Charon" }), tune()));
  assert(!isCurrentRender(render(), tune({ tts_voice_name: null })));
});

Deno.test("uniqueIds keeps first occurrences and drops blanks", () => {
  assertEquals(uniqueIds(["a", null, "b", "a", "", undefined]), ["a", "b"]);
});

// ---------------------------------------------------------------------------------------------
// Name mode
// ---------------------------------------------------------------------------------------------

Deno.test("nameModeRows: catalog first, then renders newest first, with built titles", () => {
  const tunes = byId(
    tune({ id: "stock", sample_name: "Ayush", featured_rank: 1, tune_url: "https://cdn/stock.mp3" }),
    tune({ id: "old", title_template: "Hello {name}" }),
    tune({ id: "new", title_template: null }),
  );
  const rows = nameModeRows({
    display: "Ayush",
    sampleTuneIds: ["stock"],
    renders: [
      render({ id: "r-old", tune_id: "old", created_at: "2026-09-01T00:00:00+00:00" }),
      render({ id: "r-new", tune_id: "new", created_at: "2026-09-10T00:00:00+00:00" }),
    ],
    tunesById: tunes,
    ownGenerations: [],
    limit: 20,
  });
  assertEquals(rows.map((r) => [r.tune.id, r.source, r.render_id]), [
    ["stock", "catalog", null],
    ["new", "render", "r-new"],
    ["old", "render", "r-old"],
  ]);
  assertEquals(rows[0].ringtone_url, "https://cdn/stock.mp3");
  assertEquals(rows[0].title, "Jai Shri Ram Ayush ji..");
  assertEquals(rows[1].title, "Ayush");
  assertEquals(rows[2].title, "Hello Ayush");
  assertEquals(rows[2].ringtone_url, "https://cdn.example/generated/t1/r1.mp3");
  assertEquals(rows.map((r) => r.voice), ["male", "male", "male"]);
  assert(rows.every((r) => r.generation_id === null));
});

Deno.test("nameModeRows: skips inactive, non-personalizable and stale-key renders", () => {
  const rows = nameModeRows({
    display: "Ram",
    sampleTuneIds: ["inactive-stock"],
    renders: [
      render({ id: "a", tune_id: "inactive" }),
      render({ id: "b", tune_id: "fixed" }),
      render({ id: "c", tune_id: "stale", assets_version: 1 }),
      render({ id: "d", tune_id: "missing" }),
      render({ id: "e", tune_id: "ok" }),
    ],
    tunesById: byId(
      tune({ id: "inactive-stock", is_active: false }),
      tune({ id: "inactive", is_active: false }),
      tune({ id: "fixed", is_personalizable: false }),
      tune({ id: "stale" }),
      tune({ id: "ok" }),
    ),
    ownGenerations: [],
    limit: 20,
  });
  assertEquals(rows.map((r) => r.render_id), ["e"]);
});

Deno.test("nameModeRows: one row per (tune, voice), catalog wins over a render of the same tune", () => {
  const rows = nameModeRows({
    display: "Ram",
    sampleTuneIds: ["t1", "t1"],
    renders: [
      render({ id: "r1", tune_id: "t1" }),
      render({ id: "r2", tune_id: "t2", created_at: "2026-09-02T00:00:00+00:00" }),
      render({ id: "r3", tune_id: "t2", created_at: "2026-09-03T00:00:00+00:00" }),
    ],
    tunesById: byId(tune({ id: "t1" }), tune({ id: "t2" })),
    ownGenerations: [],
    limit: 20,
  });
  assertEquals(rows.map((r) => `${r.tune.id}:${r.source}:${r.render_id}`), ["t1:catalog:null", "t2:render:r3"]);
});

Deno.test("nameModeRows: the caller's own rows give generation_id and their stored title", () => {
  const rows = nameModeRows({
    display: "ayush",
    sampleTuneIds: ["stock"],
    renders: [render({ id: "r1", tune_id: "t1" })],
    tunesById: byId(tune({ id: "t1" }), tune({ id: "stock" })),
    ownGenerations: [
      generation({ id: "g-old", render_id: "r1", title: "Old title", created_at: "2026-09-01T00:00:00+00:00" }),
      generation({
        id: "g-new",
        render_id: "r1",
        title: "Jai Shri Ram Ayush ji..",
        created_at: "2026-09-05T00:00:00+00:00",
      }),
      generation({ id: "g-stock", tune_id: "stock", render_id: null, title: null }),
      generation({ id: "g-other", tune_id: "t1", render_id: "r-other" }),
    ],
    limit: 20,
  });
  const stock = rows.find((r) => r.tune.id === "stock");
  const rendered = rows.find((r) => r.render_id === "r1");
  assertEquals(stock?.generation_id, "g-stock");
  assertEquals(stock?.title, "Jai Shri Ram ayush ji..");
  assertEquals(rendered?.generation_id, "g-new");
  assertEquals(rendered?.title, "Jai Shri Ram Ayush ji..");
});

Deno.test("nameModeRows: limit caps the list and rows carry no user ids", () => {
  const tunes: TuneRow[] = [];
  const renders: RenderRow[] = [];
  for (let i = 0; i < 30; i++) {
    tunes.push(tune({ id: `t${i}` }));
    renders.push(
      render({ id: `r${i}`, tune_id: `t${i}`, created_at: new Date(Date.UTC(2026, 8, 1, 0, i)).toISOString() }),
    );
  }
  const rows = nameModeRows({
    display: "Ram",
    sampleTuneIds: [],
    renders,
    tunesById: byId(...tunes),
    ownGenerations: [],
    limit: NAME_MODE_MAX_ROWS,
  });
  assertEquals(rows.length, NAME_MODE_MAX_ROWS);
  assertEquals(rows[0].render_id, "r29");
  const json = JSON.stringify(rows);
  assert(!json.includes("user_id"));
  assert(!json.includes("tts_voice"));
});

// ---------------------------------------------------------------------------------------------
// Mine mode
// ---------------------------------------------------------------------------------------------

Deno.test("mineModeRows: newest first, one row per (tune, name), generation ids kept", () => {
  const rows = mineModeRows({
    generations: [
      generation({ id: "g1", created_at: "2026-09-01T00:00:00+00:00" }),
      generation({ id: "g2", created_at: "2026-09-03T00:00:00+00:00" }),
      generation({
        id: "g3",
        name_normalized: "lakshya",
        name_display: "Lakshya",
        title: null,
        render_id: "r2",
        created_at: "2026-09-02T00:00:00+00:00",
      }),
    ],
    rendersById: new Map([
      ["r1", render({ id: "r1" })],
      ["r2", render({ id: "r2", public_url: "https://cdn/r2.mp3", voice: "Female" })],
    ]),
    tunesById: byId(tune()),
    limit: 50,
  });
  assertEquals(rows.map((r) => r.generation_id), ["g2", "g3"]);
  assertEquals(rows[1].title, "Jai Shri Ram Lakshya ji..");
  assertEquals(rows[1].ringtone_url, "https://cdn/r2.mp3");
  assertEquals(rows[1].voice, "female");
  assertEquals(rows[0].created_at, "2026-09-03T00:00:00+00:00");
});

Deno.test("mineModeRows: sample-name rows play the stock tune; missing renders and inactive tunes are skipped", () => {
  const rows = mineModeRows({
    generations: [
      generation({ id: "sample", render_id: null, name_normalized: "shyam", name_display: "Shyam" }),
      generation({ id: "gone", render_id: "r-missing", name_normalized: "x" }),
      generation({ id: "inactive", tune_id: "t-off", name_normalized: "y" }),
    ],
    rendersById: new Map(),
    tunesById: byId(tune(), tune({ id: "t-off", is_active: false })),
    limit: 50,
  });
  assertEquals(rows.length, 1);
  assertEquals(rows[0].source, "catalog");
  assertEquals(rows[0].ringtone_url, "https://cdn.example/stock/t1.mp3");
  assertEquals(rows[0].render_id, null);
});

Deno.test("mineModeRows: title falls back to the built title, then the typed name; limit applies", () => {
  const rows = mineModeRows({
    generations: [
      generation({
        id: "a",
        title: null,
        name_display: "Ram",
        name_normalized: "ram",
        created_at: "2026-09-25T00:00:00+00:00",
      }),
      generation({
        id: "b",
        tune_id: "t2",
        render_id: "r2",
        title: null,
        name_display: "Ravi",
        name_normalized: "ravi",
      }),
    ],
    rendersById: new Map([["r1", render()], ["r2", render({ id: "r2", tune_id: "t2" })]]),
    tunesById: byId(tune(), tune({ id: "t2", title_template: null })),
    limit: 50,
  });
  assertEquals(rows.map((r) => r.title), ["Jai Shri Ram Ram ji..", "Ravi"]);
  assertEquals(
    mineModeRows({
      generations: [generation({ id: "a" }), generation({ id: "b", tune_id: "t2" })],
      rendersById: new Map([["r1", render()]]),
      tunesById: byId(tune(), tune({ id: "t2" })),
      limit: 1,
    }).length,
    1,
  );
});

Deno.test("parseRequest rejects a non-object body with ApiError", () => {
  assertThrows(() => parseRequest("x"), ApiError, "JSON object");
});
