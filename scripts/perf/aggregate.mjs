export function aggregate(samples_ms, { dropWarmup = true } = {}) {
  if (!Array.isArray(samples_ms) || samples_ms.length === 0) {
    throw new Error("aggregate: samples_ms must be a non-empty array");
  }
  const dropped = [];
  let work = samples_ms.slice();
  if (dropWarmup && work.length >= 1) {
    dropped.push(`warmup:${work[0]}`);
    work = work.slice(1);
  }
  if (work.length >= 5) {
    const min = Math.min(...work);
    const max = Math.max(...work);
    const minIdx = work.indexOf(min);
    work.splice(minIdx, 1);
    dropped.push(`min:${min}`);
    const maxIdx = work.indexOf(max);
    work.splice(maxIdx, 1);
    dropped.push(`max:${max}`);
  }
  if (work.length === 0) {
    throw new Error("aggregate: no samples remain after drops");
  }
  const sorted = work.slice().sort((a, b) => a - b);
  const n = sorted.length;
  const mean = sorted.reduce((s, v) => s + v, 0) / n;
  const median_ms = n % 2 === 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[(n - 1) / 2];
  const rank = 0.95 * (n - 1);
  const lo = Math.floor(rank);
  const hi = Math.ceil(rank);
  const p95_ms = lo === hi ? sorted[lo] : sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
  const variance = n > 1
    ? sorted.reduce((s, v) => s + (v - mean) ** 2, 0) / (n - 1)
    : 0;
  const stddev_ms = Math.sqrt(variance);
  return {
    n,
    samples_ms: sorted,
    mean_ms: round(mean),
    median_ms: round(median_ms),
    p95_ms: round(p95_ms),
    stddev_ms: round(stddev_ms),
    dropped,
  };
}

function round(value) {
  return Math.round(value * 100) / 100;
}
