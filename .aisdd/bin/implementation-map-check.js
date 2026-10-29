#!/usr/bin/env node
'use strict';

/**
 * Minimal, dependency-free readiness gate for an implementation map.
 * It verifies that an Agent has made code-level choices explicit before
 * task-planner turns the map into executable tasks. It never writes source.
 */
const fs = require('fs');
const path = require('path');

function fail(message) { process.stderr.write(`implementation-map-check: ${message}\n`); process.exitCode = 2; }
function toPosix(value) { return value.split(path.sep).join('/'); }

function parseArgs(argv) {
  const result = { root: '.', map: null };
  for (let index = 2; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === '--map') result.map = argv[++index];
    else if (!token.startsWith('-') && result.root === '.') result.root = token;
    else throw new Error(`unknown argument: ${token}`);
  }
  result.root = path.resolve(result.root);
  result.map = path.resolve(result.root, result.map || '.aisdd/workspaces/session_xxx/implementation-map.md');
  return result;
}

function parseFrontmatter(markdown) {
  const match = markdown.match(/^---\s*\n([\s\S]*?)\n---\s*\n?/);
  if (!match) return { fields: {}, body: markdown };
  const fields = {};
  for (const line of match[1].split('\n')) {
    const found = line.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
    if (found) fields[found[1]] = found[2].trim().replace(/^['"]|['"]$/g, '');
  }
  return { fields, body: markdown.slice(match[0].length) };
}

function entryBlocks(body) {
  const pattern = /^##\s+(IM-[A-Za-z0-9-]+)\s*[:：].*$/gm;
  const matches = [...body.matchAll(pattern)];
  return matches.map((match, index) => ({
    id: match[1],
    body: body.slice(match.index, index + 1 < matches.length ? matches[index + 1].index : body.length),
  }));
}

function headingPresent(body, title) {
  return new RegExp(`^###\\s+${title}\\s*$`, 'm').test(body);
}

function validate(root, mapPath) {
  const errors = [];
  const warnings = [];
  if (!fs.existsSync(mapPath)) return { valid: false, errors: [`map not found: ${mapPath}`], warnings, entries: [] };
  const markdown = fs.readFileSync(mapPath, 'utf8');
  const { fields, body } = parseFrontmatter(markdown);
  for (const required of ['schema_version', 'status', 'tech_design', 'generated_at']) {
    if (!fields[required]) errors.push(`missing frontmatter ${required}`);
  }
  if (fields.status !== 'implementation_ready') errors.push(`status must be implementation_ready, got ${fields.status || 'missing'}`);
  const entries = entryBlocks(body);
  if (!entries.length) errors.push('no IM-xxx entries found');
  const requiredHeadings = ['Traceability', 'Code targets', 'Implementation sketch', 'Control flow and rules', 'Tests', 'Evidence', 'Blockers'];
  const resultEntries = entries.map((entry) => {
    const entryErrors = [];
    for (const heading of requiredHeadings) if (!headingPresent(entry.body, heading)) entryErrors.push(`missing section: ${heading}`);
    if (!/\bFR-[A-Za-z0-9-]+\b/.test(entry.body)) entryErrors.push('missing FR reference');
    if (!/\bEP-[A-Za-z0-9-]+\b/.test(entry.body)) entryErrors.push('missing EP reference');
    if (!/\|\s*(CREATE|MODIFY|DELETE)\s*\|/i.test(entry.body)) entryErrors.push('Code targets needs CREATE/MODIFY/DELETE table rows');
    if (!/```(?:java|kotlin|typescript|javascript|python|go|csharp|sql|text)\b[\s\S]*?```/i.test(entry.body)) entryErrors.push('missing language-tagged implementation sketch or pseudocode block');
    const blocker = entry.body.match(/###\s+Blockers\s*\n([\s\S]*?)(?=^###\s|^##\s|(?![\s\S]))/m);
    if (!blocker) entryErrors.push('missing blockers body');
    else if (!/^(?:-|\|).*\b(none|无)\b/im.test(blocker[1])) entryErrors.push('Blockers must explicitly state none/无 before implementation can start');
    const evidencePaths = [...entry.body.matchAll(/`((?:src|app|internal|lib|cmd|packages)\/[^`]+)`/g)].map((match) => match[1]);
    for (const evidencePath of evidencePaths) if (!fs.existsSync(path.join(root, evidencePath))) warnings.push(`${entry.id}: evidence path missing: ${evidencePath}`);
    if (/待确认|待联调|TODO\b|TBD\b/i.test(entry.body)) entryErrors.push('contains unresolved marker (待确认/待联调/TODO/TBD)');
    errors.push(...entryErrors.map((message) => `${entry.id}: ${message}`));
    return { id: entry.id, valid: entryErrors.length === 0, evidencePaths };
  });
  return { checkedAt: new Date().toISOString(), map: toPosix(path.relative(root, mapPath)), status: fields.status || null, entries: resultEntries, errors, warnings, valid: errors.length === 0 };
}

function main() {
  let args;
  try { args = parseArgs(process.argv); } catch (error) { fail(error.message); return; }
  const result = validate(args.root, args.map);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.valid) process.exitCode = 2;
}
main();
