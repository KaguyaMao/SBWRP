import fs from "node:fs";

const schema = JSON.parse(fs.readFileSync(new URL("./schema/physics-event.schema.json", import.meta.url), "utf8"));
const ledger = fs.readFileSync(new URL("./events.jsonl", import.meta.url), "utf8");
const pathRe = new RegExp(schema.$defs.repositoryPath.pattern);
const commitRe = /^([0-9a-f]{40}|WORKTREE)$/;
const shaRe = /^[0-9a-f]{64}$/;
const dateRe = /^\d{4}-\d{2}-\d{2}$/;
const lines = ledger.trim() ? ledger.trim().split("\n") : [];
const ids = new Set();
const errors = [];

function fail(id, message) {
  errors.push(`${id}: ${message}`);
}

lines.forEach((line, index) => {
  let event;
  try {
    event = JSON.parse(line);
  } catch (error) {
    fail(`line ${index + 1}`, `invalid JSON: ${error.message}`);
    return;
  }
  const id = event.event_id || `line ${index + 1}`;
  for (const key of schema.required) {
    if (!(key in event)) fail(id, `missing ${key}`);
  }
  for (const key of Object.keys(event)) {
    if (!(key in schema.properties)) fail(id, `unknown property ${key}`);
  }
  if (ids.has(event.event_id)) fail(id, "duplicate event_id");
  ids.add(event.event_id);
  if (!schema.properties.kind.enum.includes(event.kind)) fail(id, "kind");
  if (!schema.properties.status.enum.includes(event.status)) fail(id, "status");
  if (!schema.properties.mcp_sync.enum.includes(event.mcp_sync)) fail(id, "mcp_sync");
  if (!dateRe.test(event.recorded_at)) fail(id, "recorded_at");
  if (!commitRe.test(event.source_commit)) fail(id, "source_commit");
  if (event.core_commit != null && !commitRe.test(event.core_commit)) fail(id, "core_commit");
  for (const field of ["definition_sha256", "model_sha256"]) {
    if (event[field] != null && !shaRe.test(event[field])) fail(id, field);
  }
  for (const listName of ["changed_paths", "references"]) {
    const list = event[listName] || [];
    if (new Set(list).size !== list.length) fail(id, `${listName} duplicates`);
    for (const item of list) if (!pathRe.test(item)) fail(id, `${listName} ${item}`);
  }
  for (const test of event.tests || []) {
    if (!["pass", "fail", "not_run", "blocked"].includes(test.result)) fail(id, "test result");
    if (test.evidence_path != null && !pathRe.test(test.evidence_path)) fail(id, "evidence_path");
    if (test.result === "pass" && !test.command) fail(id, "pass without command");
  }
  if (event.status === "manual_passed" && (event.user_confirmed !== true || !["A", "B", "C", "D"].includes(event.manual_gate))) {
    fail(id, "manual_passed requires user_confirmed and gate A-D");
  }
  if (event.status === "approved" && event.user_confirmed !== true) fail(id, "approved requires user_confirmed");
  if (["pending", "unavailable", "not_run", "synced", "mismatch"].includes(event.status)) {
    fail(id, "sync/test result used as status");
  }
});

if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log(`validated ${lines.length} events`);
