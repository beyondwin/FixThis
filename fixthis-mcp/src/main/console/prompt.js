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
              window.alert(message);
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

            function promptSourceConfidence(candidate, target) {
              if (target?.type === 'visual_area') return 'low';
              return String(candidate?.confidence || 'unknown').toLowerCase();
            }

            function promptSourceLocation(candidate) {
              const file = promptScalarValue(candidate?.file);
              if (!file) return 'unknown';
              return file + (candidate.line ? ':' + candidate.line : '');
            }

            function promptLikelySources(sourceCandidates, target) {
              const lines = [];
              if (!(sourceCandidates || []).length) {
                return ['     No source candidate from current evidence; search by target labels and request.'];
              }
              (sourceCandidates || []).slice(0, 3).forEach((candidate, index) => {
                lines.push('     ' + (index + 1) + '. ' + promptSourceLocation(candidate) + ' (' + promptSourceConfidence(candidate, target) + ' confidence)');
                if ((candidate.matchedTerms || []).length) {
                  lines.push('        matched: ' + candidate.matchedTerms.join(', '));
                }
                if ((candidate.matchReasons || []).length) {
                  lines.push('        reasons: ' + candidate.matchReasons.join(', '));
                }
              });
              return lines;
            }

            function currentAnnotationsPrompt(annotations = currentPromptAnnotations()) {
              if (!state.session || annotations.length === 0) {
                throw new Error('Select a history item with annotations before sending it to an agent.');
              }
              const summary = selectedHistorySummary();
              const sessionLabel = summary ? formatSessionLabel(summary, 0) : 'Selected history';
              const lines = [
                'FixThis feedback handoff',
                '',
                'History: ' + sessionLabel,
                'Package: ' + (state.session.packageName || 'unknown'),
                'Annotations: ' + annotations.length,
                '',
                'Please inspect the UI feedback below and apply the requested fixes.'
              ];
              annotations.forEach((item, index) => {
                const bounds = promptItemBounds(item);
                lines.push(
                  '',
                  String(index + 1) + '. ' + promptItemTitle(item, index),
                  '   Severity: ' + annotationSeverity(item),
                  '   Status: ' + statusLabel(annotationStatus(item)),
                  '   Target: ' + (item.targetType ? pendingTargetLabel(item) : targetLabel(item)),
                  '   Bounds: ' + (bounds ? formatBounds(bounds) : 'unknown'),
                  ...promptNodeEvidence(item.selectedNode),
                  ...promptTargetEvidence(item),
                  '   Likely Source:',
                  ...promptLikelySources(item.sourceCandidates, item.target),
                  '   Comment: ' + (String(item.comment || '').trim() || 'No comment')
                );
              });
              return lines.join('\n');
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
