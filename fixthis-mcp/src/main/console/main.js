            selectToolButton.addEventListener('click', enterSelectMode);
            annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));
            zoomOutButton.addEventListener('click', () => setPreviewZoom(previewZoom - PreviewZoomStep));
            zoomInButton.addEventListener('click', () => setPreviewZoom(previewZoom + PreviewZoomStep));
            addItemButton.addEventListener('click', () => {
              try {
                createAnnotationFromSelection(currentSelection);
              } catch (cause) {
                showError(cause);
              }
            });
            copyPromptButton.addEventListener('click', () => copyPrompt().catch(showError));
            sendAgentButton.addEventListener('click', () => sendAgentPrompt().catch(showError));
            connectionPrimaryAction.addEventListener('click', () => handleConnectionPrimaryAction().catch(showError));
            document.getElementById('refreshDevicesButton').addEventListener('click', () => {
              refreshDevices()
                .then(refreshConnection)
                .catch(showError);
            });
            document.getElementById('disconnectDeviceButton').addEventListener('click', () => disconnectDevice().catch(showError));
            devicePicker.addEventListener('change', () => selectDevice().catch(showError));
            previewIntervalSelect.addEventListener('change', () => {
              localStorage.setItem(PreviewIntervalStorageKey, previewIntervalSelect.value);
              startLivePreviewPolling();
            });
            document.addEventListener('keydown', handleGlobalShortcut);
            // ALH-1: warn user if they try to leave with unsaved pending items.
            window.addEventListener('beforeunload', (e) => {
              if (shouldGuardUnload(pendingFeedbackItems.length)) {
                e.preventDefault();
                e.returnValue = '저장하지 않은 어노테이션이 있습니다. 정말 떠나시겠습니까?';
                return e.returnValue;
              }
            });
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
              startLivePreviewPolling();
              startSessionsPolling();
            });
            clearSelectionButton.addEventListener('click', clearSelection);
            cancelAddFlowButton.addEventListener('click', cancelAddItemsFlow);
            clearDraftButton.addEventListener('click', () => clearDraft().catch(showError));
            comment.addEventListener('input', updateSelectedAnnotationComment);

            function friendlyErrorMessage(message) {
              const raw = String(message || '');
              const lower = raw.toLowerCase();
              if (
                raw.includes('Bridge closed before sending a response') ||
                (lower.includes('bridge') && lower.includes('timed out')) ||
                raw.includes('Could not connect to FixThis bridge')
              ) {
                return 'Connection paused. Your work is saved.';
              }
              if (raw.includes('DEVICE_NOT_AVAILABLE')) return 'Check your phone, then try again.';
              return raw;
            }

            function showError(cause) {
              clearSuccessStatus();
              error.textContent = friendlyErrorMessage(cause && cause.message ? cause.message : cause);
            }

            initializePreviewIntervalSelect();
            applyPreviewZoom();
            refresh()
              .then(() => {
                // ALH-1: Auto-restore pending items from localStorage after session attach.
                // TODO(A.6 follow-up): show recovery banner / explicit user accept before exposing
                // restored items in the UI. Banner UX deferred — current behavior auto-restores.
                if (state.session?.sessionId) {
                  const restored = restorePendingItems(state.session.sessionId);
                  if (restored.length > 0) {
                    pendingFeedbackItems = restored;
                  }
                }
                if (shouldAutoFetchPreview()) return refreshPreview();
                return null;
              })
              .then(() => {
                checkServerStaleness().catch(() => { /* silent */ });
                startHeartbeatPolling();
                startLivePreviewPolling();
                startSessionsPolling();
              })
              .catch(showError);
