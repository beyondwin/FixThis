import fs from "node:fs";

const ENV_KEYS = ["os", "arch", "cpu_model", "ram_mb", "jdk", "node"];

export function comparePerf(baseline, current) {
  const warnings = [];
  for (const key of ENV_KEYS) {
    if (baseline.env?.[key] !== current.env?.[key]) {
      warnings.push(`env.${key} differs: baseline=${baseline.env?.[key]} current=${current.env?.[key]}`);
    }
  }
  const baselineByKey = new Map(baseline.results.map((r) => [r.key, r]));
  const rows = [];
  const regressed = [];
  const improved = [];
  for (const cur of current.results) {
    const base = baselineByKey.get(cur.key);
    if (!base) {
      rows.push({ key: cur.key, verdict: "NEW", deltaMs: null, deltaPct: null });
      continue;
    }
    const deltaMs = cur.median_ms - base.median_ms;
    const deltaPct = (deltaMs / base.median_ms) * 100;
    const noiseBand = Math.max(2 * base.stddev_ms, 0.02 * base.median_ms);
    const regressThreshold = cur.regress_threshold_pct ?? base.regress_threshold_pct ?? 10;
    const improveThreshold = cur.improve_threshold_pct ?? base.improve_threshold_pct ?? 5;

    let verdict = "NEUTRAL";
    if (deltaMs > noiseBand && deltaPct > regressThreshold) {
      verdict = "REGRESS";
      regressed.push(cur.key);
    } else if (-deltaMs > noiseBand && -deltaPct >= improveThreshold) {
      verdict = "IMPROVE";
      improved.push(cur.key);
    }
    rows.push({
      key: cur.key,
      baselineMedianMs: base.median_ms,
      currentMedianMs: cur.median_ms,
      deltaMs: round(deltaMs),
      deltaPct: round(deltaPct),
      noiseBandMs: round(noiseBand),
      regressThresholdPct: regressThreshold,
      verdict,
    });
  }
  return { rows, regressed, improved, warnings, exitCode: regressed.length > 0 ? 1 : 0 };
}

export function renderMarkdown(result) {
  const lines = [];
  lines.push("| Scenario | Baseline (median) | Current (median) | Delta | Verdict |");
  lines.push("| --- | --- | --- | --- | --- |");
  for (const r of result.rows) {
    const verdictTag = r.verdict === "REGRESS" ? "❌ REGRESS" : r.verdict === "IMPROVE" ? "✅ IMPROVE" : r.verdict;
    const delta = r.deltaMs == null ? "—" : `${r.deltaMs >= 0 ? "+" : ""}${r.deltaMs} ms (${r.deltaPct >= 0 ? "+" : ""}${r.deltaPct}%)`;
    lines.push(`| ${r.key} | ${r.baselineMedianMs ?? "—"} ms | ${r.currentMedianMs ?? "—"} ms | ${delta} | ${verdictTag} |`);
  }
  if (result.warnings.length > 0) {
    lines.push("");
    lines.push("**Environment warnings:**");
    for (const w of result.warnings) lines.push(`- ${w}`);
  }
  return lines.join("\n") + "\n";
}

function round(v) {
  return Math.round(v * 100) / 100;
}

const isMain = import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("compare-perf.mjs");
if (isMain) {
  const [, , baselinePath, currentPath] = process.argv;
  if (!baselinePath || !currentPath) {
    console.error("Usage: compare-perf.mjs <baseline.json> <current.json>");
    process.exit(2);
  }
  const baseline = JSON.parse(fs.readFileSync(baselinePath, "utf8"));
  const current = JSON.parse(fs.readFileSync(currentPath, "utf8"));
  const result = comparePerf(baseline, current);
  process.stdout.write(renderMarkdown(result));
  process.exit(result.exitCode);
}
