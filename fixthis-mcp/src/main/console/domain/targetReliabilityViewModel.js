// @requires (none)
function targetReliabilityBadgeModel(targetReliability) {
  const confidence = String(targetReliability?.confidence || 'unknown').toLowerCase();
  const score = Number(targetReliability?.score);
  const scoreLabel = Number.isFinite(score) ? `${Math.round(score * 100)}%` : '';
  if (confidence === 'high') return { confidence, label: 'High', tone: 'good', scoreLabel };
  if (confidence === 'medium') return { confidence, label: 'Medium', tone: 'warn', scoreLabel };
  if (confidence === 'low') return { confidence, label: 'Low', tone: 'bad', scoreLabel };
  return { confidence: 'unknown', label: 'Unknown', tone: 'muted', scoreLabel: '' };
}
