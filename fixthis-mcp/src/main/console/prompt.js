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

            function compactRiskToken(candidate, target) {
              const flags = (candidate && candidate.riskFlags) || [];
              if (flags.length > 0) {
                return String(flags[0]).toLowerCase().replace(/_/g, '-');
              }
              if (target && target.type === 'visual_area') return 'area-selection';
              return null;
            }

            function compactSourceLine(item) {
              const candidate = (item.sourceCandidates || [])[0];
              if (!candidate) return null;
              const file = promptScalarValue(candidate.file);
              if (!file) return null;
              const location = file + (candidate.line ? ':' + candidate.line : '');
              const confidence = String(candidate.confidence || 'unknown').toLowerCase();
              const why = compactReasonTokens(candidate.matchReasons);
              const risk = compactRiskToken(candidate, item.target);
              const parts = ['src? ' + location + ' ' + confidence];
              if (why) parts.push('why=' + why);
              if (risk) parts.push('risk=' + risk);
              return '   ' + parts.join('; ');
            }

            function compactTargetLine(item, isOverlap) {
              const node = item.selectedNode || {};
              const role = promptScalarValue(node.role) || (item.target && item.target.type === 'visual_area' ? 'Area' : 'Node');
              const label = (node.text && node.text[0]) || (node.contentDescription && node.contentDescription[0]) || node.testTag || '(unlabelled)';
              const bounds = promptItemBounds(item);
              const base = '   target: ' + role + ' "' + label + '" bounds=' + (bounds ? formatBounds(bounds) : 'unknown');
              return isOverlap ? (base + '; targetRisk=overlap') : base;
            }

            function compactItemLines(item, marker, isOverlap) {
              const lines = [];
              const title = (String(item.comment || '').split('\n')[0] || '').trim() || promptItemTitle(item, marker - 1);
              lines.push(String(marker) + '. [marker ' + marker + '] ' + title);
              lines.push(compactTargetLine(item, isOverlap));
              const sourceLine = compactSourceLine(item);
              if (sourceLine) lines.push(sourceLine);
              return lines;
            }

            function compactScreenHeader(screenId, screen) {
              const lines = ['Screen ' + screenId + ': ' + (screen && screen.displayName ? screen.displayName : 'Screen')];
              const screenshotPath = screen && screen.screenshot && (screen.screenshot.desktopFullPath || screen.screenshot.fullPath);
              if (screenshotPath) lines.push('screenshot: ' + screenshotPath);
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
              (state.session.screens || []).forEach(screen => {
                if (screen && screen.screenId) screensById[screen.screenId] = screen;
              });
              const grouped = {};
              list.forEach(item => {
                const id = item.screenId || 'unknown-screen';
                if (!grouped[id]) grouped[id] = [];
                grouped[id].push(item);
              });
              let counter = 0;
              Object.keys(grouped).forEach(screenId => {
                lines.push.apply(lines, compactScreenHeader(screenId, screensById[screenId]));
                lines.push('');
                const overlapGroups = compactDetectOverlap(grouped[screenId], 1);
                overlapGroups.forEach((group, groupIdx) => {
                  const isOverlap = group.length > 1;
                  if (isOverlap) {
                    lines.push('Overlap group ' + (groupIdx + 1) + ' (resolve one marker at a time):');
                  }
                  group.forEach(item => {
                    counter += 1;
                    lines.push.apply(lines, compactItemLines(item, counter, isOverlap));
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
              } finally {
                promptActionInFlight = false;
                updateComposerState();
              }
            }

            async function copyPrompt() {
              error.textContent = '';
              if (promptActionInFlight) return;
              ensurePromptAnnotationsAvailable();
              promptActionInFlight = true;
              updateComposerState();
              try {
                if (addItemsFlow) {
                  await persistPendingFeedbackItems({ onlyWrittenComments: true });
                }
                await copyTextToClipboard(currentAnnotationsPrompt());
              } finally {
                promptActionInFlight = false;
                updateComposerState();
              }
            }
