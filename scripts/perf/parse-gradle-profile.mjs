const TOTAL_RE = /Total Build Time[\s\S]*?<td[^>]*>([0-9.]+)\s*(ms|s|m|h)/i;
const STARTED_RE = /Started at[^<]*<\/td>\s*<td[^>]*>([^<]+)</i;
const GENERATED_AT_RE = /\bat\s+([A-Za-z]+\s+\d+,\s+\d{4},\s+\d+:\d+:\d+\s+[AP]M)/i;

const UNIT_MS = { ms: 1, s: 1000, m: 60_000, h: 3_600_000 };

export function parseGradleProfile(html) {
  if (!html || typeof html !== "string" || html.trim().length === 0) {
    throw new Error("parseGradleProfile: empty input");
  }
  const totalMatch = TOTAL_RE.exec(html);
  if (!totalMatch) {
    throw new Error("parseGradleProfile: 'Total Build Time' row not found");
  }
  const totalMs = Number(totalMatch[1]) * (UNIT_MS[totalMatch[2].toLowerCase()] ?? 1);
  const startedMatch = STARTED_RE.exec(html);
  let startedAt;
  if (startedMatch) {
    startedAt = new Date(startedMatch[1].trim()).toISOString();
  } else {
    const generatedMatch = GENERATED_AT_RE.exec(html);
    startedAt = generatedMatch
      ? new Date(generatedMatch[1].trim()).toISOString()
      : new Date().toISOString();
  }
  return { totalMs, startedAt };
}
