// @requires state.js, annotations.js, viewmodel/reliabilityPresentation.js, viewmodel/annotationPresentation.js
            function promptReadinessState() {
              if (pollingUseCases.getState().promptActionInFlight) {
                return {
                  state: 'busy',
                  label: 'Preparing handoff...',
                  title: 'Preparing the local handoff. Buttons are disabled until this finishes.',
                };
              }
              const annotations = toolbarAnnotations();
              if (!state.session || annotations.length === 0) {
                return {
                  state: 'empty',
                  label: 'No annotations ready',
                  title: 'Add an annotation to prepare an agent handoff.',
                };
              }
              const unsent = annotations.filter(item => item.delivery !== 'sent');
              const ready = unsent.filter(hasWrittenAnnotationComment);
              const missing = unsent.length - ready.length;
              const warningCount = annotations.reduce((total, item) => total + reliabilityWarnings(item).length, 0);
              if (ready.length > 0) {
                const itemKind = draftFlow() ? 'draft' : 'saved';
                return {
                  state: missing > 0 ? 'blocked' : (warningCount > 0 ? 'warn' : 'ready'),
                  label: countLabel(ready.length, 'ready', 'ready') +
                    (missing > 0 ? ' · ' + countLabel(missing, 'missing comment', 'missing comments') : '') +
                    (missing === 0 && warningCount > 0 ? ' · ' + countLabel(warningCount, 'target warning', 'target warnings') : ''),
                  title: 'Ready to hand off ' + countLabel(ready.length, itemKind + ' annotation', itemKind + ' annotations') +
                    (missing > 0 ? '. ' + countLabel(missing, 'annotation needs', 'annotations need') + ' a comment.' : '') +
                    (missing === 0 && warningCount > 0 ? ' Reliability warnings will be included in the handoff.' : '.'),
                };
              }
              if (missing > 0) {
                return {
                  state: 'blocked',
                  label: countLabel(missing, 'missing comment', 'missing comments'),
                  title: 'Add a comment before copying or saving feedback.',
                };
              }
              if (annotations.some(item => lifecyclePhase(item) === 'sent_modified')) {
                return {
                  state: 'modified',
                  label: 'Re-save needed',
                  title: 'Edits changed after handoff. Re-save before agent work.',
                };
              }
              if (annotations.some(item => item.delivery === 'sent')) {
                return {
                  state: 'sent',
                  label: 'Saved to MCP',
                  title: 'Saved to MCP. Agent can read this local queue.',
                };
              }
              return {
                state: 'empty',
                label: 'No annotations ready',
                title: 'Annotate a UI target and add a comment to enable handoff.',
              };
            }

            function renderPromptReadiness() {
              if (!promptReadiness) return;
              const readiness = promptReadinessState();
              promptReadiness.dataset.state = readiness.state;
              promptReadiness.textContent = readiness.label;
              promptReadiness.title = readiness.title;
              copyPromptButton.title = readiness.title;
              sendAgentButton.title = readiness.title;
            }
