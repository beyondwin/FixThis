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

            async function persistAndCollectItemIds() {
                const before = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
                const beforeIds = new Set(before.map(item => item.itemId));
                if (addItemsFlow) {
                    flushFocusedPendingComment();
                    if (pendingFeedbackItems.some(item => !hasWrittenAnnotationComment(item))) {
                        throw new Error('Add a comment to every annotation before saving.');
                    }
                    await persistPendingFeedbackItems();
                }
                const after = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
                const newlyPersisted = after.filter(item => !beforeIds.has(item.itemId)).map(item => item.itemId);
                if (newlyPersisted.length === 0) {
                    // No new items: fall back to currently-pending (already-sent or already-drafted) prompt selection.
                    const fallback = currentPromptAnnotations().map(item => item.itemId).filter(Boolean);
                    if (fallback.length === 0) throw new Error('Add a comment to at least one annotation before sending.');
                    return fallback;
                }
                return newlyPersisted;
            }

            async function fetchHandoffPreview(sessionId, itemIds) {
                const headers = new Headers({ 'Content-Type': 'application/json' });
                const token = window.FixThisConsoleConfig?.consoleToken;
                if (token) headers.set('X-FixThis-Console-Token', token);
                const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/handoff-preview`, {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({ itemIds }),
                });
                if (!response.ok) {
                    const text = await response.text();
                    throw new Error(`Preview fetch failed (${response.status}): ${text}`);
                }
                return response.text();
            }

            async function markItemsHandedOff(sessionId, itemIds) {
                return await requestJson(
                    '/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off',
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ itemIds }),
                    }
                );
            }

            async function copyPrompt() {
                if (promptActionInFlight) return;
                await withMutationLock(async () => {
                    clearSuccessStatus();
                    ensurePromptAnnotationsAvailable();
                    promptActionInFlight = true;
                    updateComposerState();
                    const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
                    const originalLabel = labelSpan ? labelSpan.textContent : null;
                    let copied = false;
                    try {
                        const itemIds = await persistAndCollectItemIds();
                        const sessionId = draftWorkspace?.context?.sessionId || state.session?.sessionId;
                        if (!sessionId) throw new Error('Feedback session context is missing. Re-capture and try again.');
                        const markdown = await fetchHandoffPreview(sessionId, itemIds);
                        await copyTextToClipboard(markdown);
                        copied = true;
                        try {
                            const updated = await markItemsHandedOff(sessionId, itemIds);
                            state.session = updated;
                            renderInspectorRegion();
                        } catch (markError) {
                            showWarning('Copied, but MCP handoff status was not updated. Copy again after the connection recovers to update item state.');
                        }
                    } finally {
                        promptActionInFlight = false;
                        updateComposerState();
                        if (copied && labelSpan) {
                            labelSpan.textContent = 'Copied ✓';
                            setTimeout(() => {
                                if (labelSpan.textContent === 'Copied ✓') labelSpan.textContent = originalLabel;
                            }, 1500);
                        }
                    }
                });
            }

            async function sendAgentPrompt() {
                if (promptActionInFlight) return;
                await withMutationLock(async () => {
                    clearSuccessStatus();
                    ensurePromptAnnotationsAvailable();
                    promptActionInFlight = true;
                    updateComposerState();
                    let sent = false;
                    try {
                        const itemIds = await persistAndCollectItemIds();
                        const sessionId = draftWorkspace?.context?.sessionId || state.session?.sessionId;
                        if (!sessionId) throw new Error('Feedback session context is missing. Re-capture and try again.');
                        const result = await requestJson('/api/agent-handoffs', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                sessionId,
                                itemIds
                            }),
                        });
                        state.session = result.session;
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
                        if (sent) showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
                    }
                });
            }
