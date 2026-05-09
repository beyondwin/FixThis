            function promptUnavailableMessage() {
              if (!state.session) return 'Select a history item before copying or sending annotations.';
              const annotations = toolbarAnnotations();
              if (!annotations.length) return 'The selected history item has no annotations to send.';
              if (!annotations.some(hasWrittenAnnotationComment)) return 'Add a comment to at least one annotation before copying or sending it.';
              return 'No completed annotations are ready to send.';
            }

            function ensurePromptAnnotationsAvailable() {
              const annotations = currentPromptAnnotations();
              if (annotations.length) return annotations;
              const message = promptUnavailableMessage();
              error.textContent = message;
              throw new Error(message);
            }

            function promptItemTitle(item, index) {
              return item.label || (item.comment ? firstLine(item.comment) : targetLabel(item)) || ('Annotation ' + (index + 1));
            }

            function promptItemBounds(item) {
              return item.bounds || boundsForTarget(item.target);
            }

            function promptListValue(values) {
              const joined = (values || [])
                .map(value => String(value || '').trim())
                .filter(Boolean)
                .join(' | ');
              return joined || null;
            }

            function promptScalarValue(value) {
              const scalar = String(value || '').trim();
              return scalar || null;
            }

            function promptNodeEvidence(node) {
              if (!node) return [];
              const lines = [];
              const textValue = promptListValue(node.text);
              const editableText = promptScalarValue(node.editableText);
              const contentDescription = promptListValue(node.contentDescription);
              const testTag = promptScalarValue(node.testTag);
              const role = promptScalarValue(node.role);
              if (textValue) lines.push('   UI Text: ' + textValue);
              if (editableText) lines.push('   Editable Text: ' + editableText);
              if (contentDescription) lines.push('   Content Description: ' + contentDescription);
              if (testTag) lines.push('   Test Tag: ' + testTag);
              if (role) lines.push('   Role: ' + role);
              return lines;
            }

            function promptTargetEvidence(item) {
              const evidence = item.targetEvidence || {};
              const lines = [];
              const hint = evidence.identityHint || {};
              const identity = [hint.composableNameHint, hint.variantHint]
                .map(promptScalarValue)
                .filter(Boolean)
                .join(':');
              if (identity) lines.push('   Identity: ' + identity);
              if (hint.stableLabel) lines.push('   Stable Label: ' + hint.stableLabel);
              if (evidence.occurrence) {
                lines.push('   Occurrence: ' + evidence.occurrence.selectedOrdinal + '/' + evidence.occurrence.count);
              }
              const interpretation = evidence.sourceInterpretation || {};
              if (interpretation.caution) lines.push('   Source Caution: ' + interpretation.caution);
              if ((evidence.warnings || []).length) lines.push('   Warnings: ' + evidence.warnings.join(', '));
              return lines;
            }

            const FIXTHIS_IOSA_THRESHOLD = 0.25;
            const FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP = 24;

            function rectArea(r) {
              if (!r) return 0;
              return Math.max(r.right - r.left, 0) * Math.max(r.bottom - r.top, 0);
            }
            function rectIntersection(a, b) {
              const left = Math.max(a.left, b.left);
              const top = Math.max(a.top, b.top);
              const right = Math.min(a.right, b.right);
              const bottom = Math.min(a.bottom, b.bottom);
              return Math.max(right - left, 0) * Math.max(bottom - top, 0);
            }
            function rectCenterDistance(a, b) {
              const ax = (a.left + a.right) / 2;
              const ay = (a.top + a.bottom) / 2;
              const bx = (b.left + b.right) / 2;
              const by = (b.top + b.bottom) / 2;
              return Math.hypot(ax - bx, ay - by);
            }
            function compactOverlapItems(item) {
              const t = item.target || {};
              const bounds = (t.boundsInWindow) || (item.bounds) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
              const isAreaSelection = (t.type === 'visual_area');
              const hasWeakLabels = !item.selectedNode || !(item.selectedNode.text && item.selectedNode.text.length);
              return { item: item, bounds: bounds, isAreaSelection: isAreaSelection, hasWeakLabels: hasWeakLabels };
            }
            function compactOverlapsBetween(a, b, density) {
              const inter = rectIntersection(a.bounds, b.bounds);
              if (a.isAreaSelection && b.isAreaSelection) return inter > 0;
              const smaller = Math.min(rectArea(a.bounds), rectArea(b.bounds));
              if (smaller > 0 && (inter / smaller) >= FIXTHIS_IOSA_THRESHOLD) return true;
              if (a.hasWeakLabels || b.hasWeakLabels) {
                const threshold = FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP * (density || 1);
                if (rectCenterDistance(a.bounds, b.bounds) <= threshold) return true;
              }
              return false;
            }
            function compactDetectOverlap(items, density) {
              if (!items || items.length < 2) return (items || []).map(it => [it]);
              const wrapped = items.map(compactOverlapItems);
              const parent = wrapped.map((_, i) => i);
              function find(i) { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
              function union(a, b) { const ra = find(a); const rb = find(b); if (ra !== rb) parent[rb] = ra; }
              for (let i = 0; i < wrapped.length; i++) {
                for (let j = i + 1; j < wrapped.length; j++) {
                  if (compactOverlapsBetween(wrapped[i], wrapped[j], density)) union(i, j);
                }
              }
              const groups = {};
              wrapped.forEach((w, i) => {
                const r = find(i);
                if (!groups[r]) groups[r] = [];
                groups[r].push(w.item);
              });
              return Object.keys(groups).map(k => groups[k]);
            }

            const FIXTHIS_REASON_TOKEN_MAP = {
              'selected text': 'text',
              'selected contentDescription': 'contentDescription',
              'selected testTag': 'tag',
              'selected testTag convention composable': 'compTag',
              'selected role': 'role',
              'nearby text': 'nearbyText',
              'nearby contentDescription': 'nearbyContentDescription',
              'nearby testTag': 'nearbyTag',
              'nearby role': 'nearbyRole',
              'activity': 'activity',
              'selected stringResource': 'stringRes',
              'arbitrary literal': 'literal',
              'legacy fallback': 'legacy'
            };

            function compactReasonTokens(reasons) {
              const seen = new Set();
              const tokens = [];
              (reasons || []).forEach(reason => {
                const token = FIXTHIS_REASON_TOKEN_MAP[String(reason || '').trim()];
                if (token && !seen.has(token)) {
                  seen.add(token);
                  tokens.push(token);
                }
              });
              return tokens.join('+');
            }

            // Task 5.2: formatBox — emits (L,T)-(R,B) [W×H] using integer dimensions
            function formatBox(bounds) {
              const l = Math.floor(bounds.left);
              const t = Math.floor(bounds.top);
              const r = Math.floor(bounds.right);
              const b = Math.floor(bounds.bottom);
              const w = Math.max(Math.floor(bounds.right - bounds.left), 0);
              const h = Math.max(Math.floor(bounds.bottom - bounds.top), 0);
              return '(' + l + ',' + t + ')-(' + r + ',' + b + ') [' + w + '×' + h + ']';
            }

            // Task 5.2: compactUiLine — replaces compactTargetLine; emits ui: role tag=tag  box=(L,T)-(R,B) [W×H]
            function compactUiLine(item, isOverlap, instanceLabel, dupRefMarker) {
              const node = item.selectedNode || {};
              const role = promptScalarValue(node.role) || (item.target && item.target.type === 'visual_area' ? 'Area' : 'Node');
              const tag = promptScalarValue(node.testTag) || '(none)';
              const bounds = (item.target && item.target.boundsInWindow) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
              let base = '  ui: ' + role + ' tag=' + tag + '  box=' + formatBox(bounds);
              if (instanceLabel) {
                base += '  instance ' + instanceLabel.index + '/' + instanceLabel.total;
              }
              if (dupRefMarker != null) {
                return base + '; targetRisk=duplicate-of-marker-' + dupRefMarker;
              }
              if (isOverlap) {
                return base + '; targetRisk=overlap';
              }
              return base;
            }

            // Task 5.4: compactCandidatesBlock — replaces compactSourceLine; returns array of lines
            function compactCandidatesBlock(item) {
              const lines = [];
              lines.push('  candidates:');
              const candidates = item.sourceCandidates || [];
              if (candidates.length === 0) {
                lines.push('    ~ unknown');
                return lines;
              }
              const rank1 = candidates[0];
              const rank2 = candidates[1];
              const computedMargin = (rank1 && rank2 && (rank1.score - rank2.score) > 0)
                ? (rank1.score - rank2.score)
                : null;
              const maxCandidates = 3;
              candidates.slice(0, maxCandidates).forEach(function(candidate, idx) {
                const rank = idx + 1;
                const file = promptScalarValue(candidate.file);
                const location = file ? (file + (candidate.line ? ':' + candidate.line : '')) : 'unknown';
                const confidence = String(candidate.confidence || 'unknown').toLowerCase();
                let line = '    ~ ' + location + '  conf=' + confidence;
                if (rank === 1) {
                  const effectiveMargin = (candidate.scoreMargin != null) ? candidate.scoreMargin : computedMargin;
                  if (effectiveMargin != null) {
                    line += '  margin=' + effectiveMargin.toFixed(2);
                  }
                  // Build matched=[...] using FIXTHIS_REASON_TOKEN_MAP, deduped, max 4
                  const reasons = candidate.matchReasons || [];
                  const seen = new Set();
                  const tokens = [];
                  reasons.forEach(function(reason) {
                    const token = FIXTHIS_REASON_TOKEN_MAP[String(reason || '').trim()];
                    if (token && !seen.has(token)) {
                      seen.add(token);
                      tokens.push(token);
                    }
                  });
                  const capped = tokens.slice(0, 4);
                  if (capped.length > 0) {
                    line += '  matched=[' + capped.join(', ') + ']';
                  }
                }
                lines.push(line);
              });
              // Caution note from rank-1 candidate (Task 5.4, parity Phase 2.5)
              const caution = rank1 && rank1.caution ? String(rank1.caution).trim() : null;
              if (caution) {
                lines.push('  note: ' + caution);
              }
              return lines;
            }

            // Task 5.5: computeInstanceLabels — mirrors Kotlin InstanceGroupingHelper.compute
            function computeInstanceLabels(items) {
              const labels = new Map(); // itemId -> {index, total}
              const leaderItemIds = new Set();

              // Filter to eligible items (have selectedNode and sourceCandidates)
              const eligible = (items || []).filter(function(item) {
                return item.selectedNode != null && (item.sourceCandidates || []).length > 0;
              });

              // Group by (fileLine + testTag)
              const groups = new Map(); // key -> [item]
              eligible.forEach(function(item) {
                const cand = item.sourceCandidates[0];
                const file = promptScalarValue(cand.file);
                const fileLine = file ? (file + (cand.line ? ':' + cand.line : '')) : '';
                const testTag = item.selectedNode.testTag || '';
                const key = fileLine + '|' + testTag;
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(item);
              });

              groups.forEach(function(group) {
                if (group.length < 2) return;
                // Sort by path.join('/')
                const ordered = group.slice().sort(function(a, b) {
                  const pa = (a.selectedNode.path || []).join('/');
                  const pb = (b.selectedNode.path || []).join('/');
                  if (pa < pb) return -1;
                  if (pa > pb) return 1;
                  return 0;
                });
                ordered.forEach(function(item, idx) {
                  labels.set(item.itemId, { index: idx + 1, total: ordered.length });
                });
                leaderItemIds.add(ordered[0].itemId);
              });

              return { labels: labels, leaderItemIds: leaderItemIds };
            }

            // Task 5.7: computeDuplicateMarkers — mirrors Kotlin DuplicateMarkerDetector.detect
            // detectorItems: Array of { itemId, markerNumber, fileLine, testTag, pathLeaves, bounds }
            function computeDuplicateMarkers(detectorItems) {
              const result = new Map(); // itemId -> canonical markerNumber
              const keyGroups = new Map(); // key string -> [detectorItem]
              (detectorItems || []).forEach(function(di) {
                const bounds = di.bounds || { left: 0, top: 0, right: 0, bottom: 0 };
                const key = (di.fileLine || '') + '|' + (di.testTag || '') + '|' + (di.pathLeaves || []).join('/') + '|' + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom;
                if (!keyGroups.has(key)) keyGroups.set(key, []);
                keyGroups.get(key).push(di);
              });
              keyGroups.forEach(function(group) {
                if (group.length < 2) return;
                const canonical = group[0].markerNumber;
                group.slice(1).forEach(function(dup) {
                  result.set(dup.itemId, canonical);
                });
              });
              return result;
            }

            // Task 5.5/5.6/5.7: compactItemLines — updated to use new helpers
            function compactItemLines(item, marker, isOverlap, instanceLabel, dupRefMarker, isInstanceLeader, groupSize) {
              const lines = [];
              const rawTitle = (String(item.comment || '').split('\n')[0] || '').trim() || promptItemTitle(item, marker - 1);
              // Task 5.3: severity prefix
              const title = (item.severity === 'high') ? '[!] ' + rawTitle : rawTitle;
              lines.push(String(marker) + '. [marker ' + marker + '] ' + title);
              lines.push(compactUiLine(item, isOverlap, instanceLabel, dupRefMarker));
              const candidatesBlock = compactCandidatesBlock(item);
              candidatesBlock.forEach(function(l) { lines.push(l); });
              // Task 5.6: collision note on group leader (suppressed for overlap groups)
              if (isInstanceLeader && groupSize >= 2 && !isOverlap) {
                lines.push('  note: ' + groupSize + ' markers map to same call site — likely list-rendered; disambiguate by instance index');
              }
              return lines;
            }

            function compactScreenHeader(screenId, screen) {
              const shortId = String(screenId).slice(0, 8);
              const lines = ['Screen ' + shortId + ': ' + (screen && screen.displayName ? screen.displayName : 'Screen')];
              const screenshotPath = screen && screen.screenshot && (screen.screenshot.desktopFullPath || screen.screenshot.fullPath);
              if (screenshotPath) lines.push('screenshot: ' + screenshotPath);
              const w = screen && screen.screenshot && screen.screenshot.width;
              const h = screen && screen.screenshot && screen.screenshot.height;
              if (w && h) lines.push('viewport: ' + w + '×' + h);
              const activityName = screen && screen.activityName;
              const displayName = screen && screen.displayName;
              if (activityName && activityName !== displayName) lines.push('activity: ' + activityName);
              return lines;
            }

            function currentAnnotationsPromptCompact(annotations) {
              const list = annotations || currentPromptAnnotations();
              if (!state.session || list.length === 0) {
                throw new Error('Select a history item with annotations before sending it to an agent.');
              }
              const lines = [
                'FixThis feedback handoff',
                '',
                'Rule: source hints are candidates; verify screenshot, target, and code before editing.',
                '',
                'Package: ' + (state.session.packageName || 'unknown'),
                'Annotations: ' + list.length,
                ''
              ];
              const screensById = {};
              (state.session.screens || []).forEach(function(screen) {
                if (screen && screen.screenId) screensById[screen.screenId] = screen;
              });
              const grouped = {};
              list.forEach(function(item) {
                const id = item.screenId || 'unknown-screen';
                if (!grouped[id]) grouped[id] = [];
                grouped[id].push(item);
              });
              let counter = 0;
              Object.keys(grouped).forEach(function(screenId) {
                lines.push.apply(lines, compactScreenHeader(screenId, screensById[screenId]));
                lines.push('');
                const itemsForScreen = grouped[screenId];

                // Task 5.5: compute instance grouping for this screen
                const instanceGrouping = computeInstanceLabels(itemsForScreen);

                const overlapGroups = compactDetectOverlap(itemsForScreen, 1);

                // Task 5.7: pre-pass to assign marker numbers, then build duplicate map
                let preCounter = counter;
                const markerByItemId = new Map();
                overlapGroups.forEach(function(group) {
                  group.forEach(function(item) {
                    preCounter += 1;
                    markerByItemId.set(item.itemId, preCounter);
                  });
                });
                const detectorItems = list.filter(function(item) {
                  return markerByItemId.has(item.itemId);
                }).map(function(item) {
                  const cand = (item.sourceCandidates || [])[0];
                  const file = cand && promptScalarValue(cand.file);
                  const fileLine = file ? (file + (cand.line ? ':' + cand.line : '')) : null;
                  const testTag = item.selectedNode && item.selectedNode.testTag;
                  const pathLeaves = (item.selectedNode && item.selectedNode.path) || [];
                  const bounds = (item.target && item.target.boundsInWindow) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
                  return {
                    itemId: item.itemId,
                    markerNumber: markerByItemId.get(item.itemId),
                    fileLine: fileLine,
                    testTag: testTag,
                    pathLeaves: pathLeaves,
                    bounds: bounds
                  };
                });
                const duplicateMap = computeDuplicateMarkers(detectorItems);

                let overlapGroupCounter = 0;
                overlapGroups.forEach(function(group) {
                  const isOverlap = group.length > 1;
                  if (isOverlap) {
                    overlapGroupCounter += 1;
                    lines.push('Overlap group ' + overlapGroupCounter + ' (resolve one marker at a time):');
                  }
                  group.forEach(function(item) {
                    counter += 1;
                    const dupRefMarker = duplicateMap.has(item.itemId) ? duplicateMap.get(item.itemId) : null;
                    // Task 5.7: suppress instance label for duplicates
                    const instanceLabel = (dupRefMarker == null) ? (instanceGrouping.labels.get(item.itemId) || null) : null;
                    const isInstanceLeader = instanceGrouping.leaderItemIds.has(item.itemId);
                    const groupSize = instanceLabel ? instanceLabel.total : 0;
                    lines.push.apply(lines, compactItemLines(item, counter, isOverlap, instanceLabel, dupRefMarker, isInstanceLeader, groupSize));
                    lines.push('');
                  });
                });
              });
              return lines.join('\n').replace(/\n+$/, '\n');
            }

            function currentAnnotationsPrompt(annotations) {
              return currentAnnotationsPromptCompact(annotations);
            }


            async function sendAgentPrompt() {
              error.textContent = '';
              if (promptActionInFlight) return;
              ensurePromptAnnotationsAvailable();
              promptActionInFlight = true;
              updateComposerState();
              let sent = false;
              try {
                if (addItemsFlow) {
                  await persistPendingFeedbackItems({ onlyWrittenComments: true });
                }
                const prompt = currentAnnotationsPrompt();
                state.session = await requestJson('/api/agent-handoffs', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ prompt: prompt })
                });
                comment.value = '';
                resetAnnotationComposerState();
                invalidatePreviewContext();
                await refreshSessions();
                render();
                startLivePreviewPolling();
                sent = true;
              } finally {
                promptActionInFlight = false;
                updateComposerState();
                if (sent) {
                  showSuccess('Saved to MCP ✓', 3000);
                }
              }
            }

            async function copyPrompt() {
              error.textContent = '';
              if (promptActionInFlight) return;
              ensurePromptAnnotationsAvailable();
              promptActionInFlight = true;
              updateComposerState();
              const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
              const originalLabel = labelSpan ? labelSpan.textContent : null;
              let copied = false;
              try {
                if (addItemsFlow) {
                  await persistPendingFeedbackItems({ onlyWrittenComments: true });
                }
                await copyTextToClipboard(currentAnnotationsPrompt());
                copied = true;
              } finally {
                promptActionInFlight = false;
                updateComposerState();
                if (copied && labelSpan) {
                  labelSpan.textContent = 'Copied ✓';
                  setTimeout(() => {
                    if (labelSpan.textContent === 'Copied ✓') {
                      labelSpan.textContent = originalLabel;
                    }
                  }, 1500);
                }
              }
            }
