# Console Test Decomposition Ledger (2026-05-14)

Source: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
Baseline total: 100 `@Test` methods

| Test method | Destination file |
|---|---|
| itemPatchUpdatesDraftAnnotation | ConsoleFeedbackItemRoutesTest.kt |
| itemPatchUsesRequestedSessionWhenCurrentSessionChanged | ConsoleFeedbackItemRoutesTest.kt |
| batchSaveUsesRequestedSessionWhenCurrentSessionChanged | ConsoleFeedbackItemRoutesTest.kt |
| agentHandoffUsesRequestedSessionWhenCurrentSessionChanged | ConsoleHandoffRoutesTest.kt |
| consoleHtmlIncludesSessionPickerControls | ConsoleAssetContractTest.kt |
| consoleHtmlOmitsToolbarNavigationControls | ConsoleAssetContractTest.kt |
| consoleHtmlUsesBrowserStudioLayout | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsStudioUsableInNarrowBrowser | ConsoleAssetContractTest.kt |
| consoleHtmlUsesModeAwareStudioInspector | ConsoleAssetContractTest.kt |
| consoleHtmlEditsSelectedAnnotationsAndFocusesComment | ConsoleAssetContractTest.kt |
| consoleHtmlResetsAnnotationComposerStateAcrossSessionActions | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsFixThisTopLevelActionsInStudioTopbar | ConsoleAssetContractTest.kt |
| consoleHtmlAddsStudioKeyboardAndAccessibilityGuards | ConsoleAssetContractTest.kt |
| consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus | ConsoleAssetContractTest.kt |
| consoleHtmlRefreshesSessionSummariesAfterSavedItemDeleteOrEdit | ConsoleAssetContractTest.kt |
| consoleHtmlReplacesPlaceholderYouLabelWithScreensCount | ConsoleAssetContractTest.kt |
| consoleHtmlGroupsSavedAnnotationsByScreenInPanel | ConsoleAssetContractTest.kt |
| consoleHtmlComposerInspectorAlsoShowsSavedAnnotations | ConsoleAssetContractTest.kt |
| consoleHtmlNoLongerFiltersSentItemsFromInspector | ConsoleAssetContractTest.kt |
| consoleHtmlIncludesSelectionHandoffWorkspace | ConsoleAssetContractTest.kt |
| consoleHtmlDoesNotRenderInternalIdsInHumanLabels | ConsoleAssetContractTest.kt |
| consoleHtmlRendersStudioSessionHistoryWithoutInternalIds | ConsoleAssetContractTest.kt |
| consoleHtmlFlushesPendingAnnotationsBeforeSessionSwitch | ConsoleAssetContractTest.kt |
| consoleUsesOptionASelectAnnotateToolsAndSimpleLabels | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsHiddenInspectorListsOutOfLayout | ConsoleAssetContractTest.kt |
| consoleHtmlShowsStartAnnotatingWhenSavedAnnotationsAreEmpty | ConsoleAssetContractTest.kt |
| consoleHtmlGivesBackToAnnotationsButtonButtonPadding | ConsoleAssetContractTest.kt |
| consoleHtmlCreatesHistorySessionBeforeAnnotatingFromEmptyState | ConsoleAssetContractTest.kt |
| consoleHtmlNoLongerFiltersReadyForAgentSessions | ConsoleAssetContractTest.kt |
| consoleHtmlRendersSavedAnnotationsWithSameListUiAfterSessionSwitch | ConsoleAssetContractTest.kt |
| consoleHtmlRendersOptionACanvasToolbar | ConsoleAssetContractTest.kt |
| consoleHtmlCountsActivePendingAnnotationsInHistory | ConsoleAssetContractTest.kt |
| consoleHtmlFocusesPendingItemWithoutDrawingUnnumberedSelectionOverlay | ConsoleAssetContractTest.kt |
| consoleHtmlImplementsSnapshotSelectionModes | ConsoleAssetContractTest.kt |
| consoleHtmlReportsNavigationCaptureErrors | ConsoleAssetContractTest.kt |
| consoleHtmlAnnotationSaveUsesCurrentSelectionPayload | ConsoleAssetContractTest.kt |
| savingDraftItemsAppendsOneScreenAndTwoItems | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsConflictWhenLiveScreenFingerprintDiffersFromFrozenPreview | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsFingerprintUnavailableHeaderWhenCurrentFingerprintIsMissing | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsServerErrorWhenLiveRecaptureThrowsIllegalArgumentException | ConsoleFeedbackItemRoutesTest.kt |
| savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsBadRequestForEmptyItemList | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsNotFoundForUnknownPreviewId | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsBadRequestForInvalidPreviewTarget | ConsoleFeedbackItemRoutesTest.kt |
| previewSaveInProgressMapsToConflict | ConsoleFeedbackItemRoutesTest.kt |
| sessionApiDoesNotCreateSessionWhenHistoryIsEmpty | ConsoleSessionRoutesTest.kt |
| agentHandoffApiSendsDraftAndClearsDraftList | ConsoleHandoffRoutesTest.kt |
| agentHandoffApiReturnsConflictWhenNoDraftItemsExist | ConsoleHandoffRoutesTest.kt |
| clearDraftApiKeepsSentItems | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForUnknownScreenId | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForUnsupportedFields | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForInvalidAreaBounds | ConsoleFeedbackItemRoutesTest.kt |
| sessionsApiListsWorkspaces | ConsoleSessionRoutesTest.kt |
| sessionsApiFiltersByPackageNameQuery | ConsoleSessionRoutesTest.kt |
| openSessionApiSwitchesCurrentSession | ConsoleSessionRoutesTest.kt |
| openSessionApiReturnsNotFoundForUnknownSessionId | ConsoleSessionRoutesTest.kt |
| sessionApiReturnsServerErrorForSessionSaveFailure | ConsoleSessionRoutesTest.kt |
| closeSessionApiClosesCurrentSession | ConsoleSessionRoutesTest.kt |
| closeSessionApiReturnsNotFoundForUnknownSessionId | ConsoleSessionRoutesTest.kt |
| navigationApiPerformsAction | ConsoleNavigationRoutesTest.kt |
| navigationApiRejectsUnknownAutomationFields | ConsoleNavigationRoutesTest.kt |
| deleteScreenApiDeletesScreenAndLinkedItems | ConsoleFeedbackItemRoutesTest.kt |
| apiSessionsResponseIncludesEtag | ConsoleEtagRoutesTest.kt |
| apiSessionsReturns304ForMatchingIfNoneMatch | ConsoleEtagRoutesTest.kt |
| apiSessionsEtagChangesAfterMutation | ConsoleEtagRoutesTest.kt |
| apiSessionResponseIncludesEtag | ConsoleEtagRoutesTest.kt |
| apiSessionReturns304ForMatchingIfNoneMatch | ConsoleEtagRoutesTest.kt |
| apiSessionWithoutCurrentReturns200NullAndNoEtag | ConsoleEtagRoutesTest.kt |
| historyPipsCollapseWorkingIntoOpen | ConsoleSessionsPollingContractTest.kt |
| historyPipDropsPointsLabel | ConsoleSessionsPollingContractTest.kt |
| consoleHtmlContainsSessionsPolling | ConsoleAssetContractTest.kt |
| consoleHtmlDeclaresPollingGlobals | ConsoleAssetContractTest.kt |
| saveToMcpToastMentionsAgentPickup | ConsoleHandoffRoutesTest.kt |
| promptActionsDoNotSilentlyDropUncommentedPendingAnnotations | ConsoleSessionsPollingContractTest.kt |
| mutationsAreWrappedInLock | ConsoleSessionsPollingContractTest.kt |
| mergeSessionIntoStatePreservesUserState | ConsoleSessionsPollingContractTest.kt |
| mergeSessionIntoStateSkipsHighlightOnBulkChange | ConsoleSessionsPollingContractTest.kt |
| startSessionsPollingIsCalledOnBoot | ConsoleSessionsPollingContractTest.kt |
| sessionsPollingDeclaresFailureBackoffConstants | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickResetsFailureCounterOnSuccess | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickIncrementsFailureCounterOnError | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickPausesAfterThreshold | ConsoleSessionsPollingContractTest.kt |
| visibilityChangeRecoversFromPolledFailure | ConsoleSessionsPollingContractTest.kt |
| withMutationLockRecoversFromPolledFailure | ConsoleSessionsPollingContractTest.kt |
| handoffPreviewEndpointReturnsMarkdownForRequestedItems | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointReturns404ForUnknownSession | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointEmitsJsonErrorBody | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointUpdatesLastHandedOffAtForItems | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointReturns404ForUnknownSession | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointRequiresConsoleToken | ConsoleHandoffRoutesTest.kt |
| agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt | ConsoleHandoffRoutesTest.kt |
| agentHandoffsRejectsLegacyPromptBody | ConsoleHandoffRoutesTest.kt |
| agentHandoffsRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| agentHandoffsFlipsOnlySpecifiedItemIdsToSentLeavesOthersAsDraft | ConsoleHandoffRoutesTest.kt |
| sessionResponseIncludesStaleAfterHandoffFalseInitially | ConsoleHandoffRoutesTest.kt |
| sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend | ConsoleHandoffRoutesTest.kt |
| sessionResponseStaleAfterHandoffFalseAfterReSave | ConsoleHandoffRoutesTest.kt |

Totals (must sum to 100):
- ConsoleFeedbackItemRoutesTest.kt: 18
- ConsoleSessionRoutesTest.kt: 8
- ConsoleHandoffRoutesTest.kt: 19
- ConsoleSessionsPollingContractTest.kt: 13
- ConsoleAssetContractTest.kt: 34
- ConsoleEtagRoutesTest.kt: 6
- ConsoleNavigationRoutesTest.kt: 2

> The per-test rows above are the source of truth. If a row's destination
> ever disagrees with this totals block, the rows win and this block must be
> recomputed (and design spec §3.1 updated to match).
