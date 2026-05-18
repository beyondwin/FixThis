// @requires state.js
            // Display-side annotations for the toolbar counter and the right Annotations panel.
            // Includes already-sent items so the count matches the sidebar Session card's lifetime total
            // (sidebar uses server-side unresolvedItemsCount, which counts by status only - not delivery).
            // The send/copy path uses currentPromptAnnotations(), which re-applies the delivery filter
            // so already-sent items are not re-sent.
            function toolbarAnnotations() {
              if (draftFlow()) return draftItemList();
              return state.session?.items || [];
            }

            function hasWrittenAnnotationComment(item) {
              return Boolean(String(item?.comment || '').trim());
            }

            function draftItemsFromValue(value) {
              if (Array.isArray(value)) return value;
              return Array.isArray(value?.items) ? value.items : [];
            }

            function commentedDraftItems(value) {
              return draftItemsFromValue(value).filter(hasWrittenAnnotationComment);
            }

            function pinOnlyDraftItems(value) {
              return draftItemsFromValue(value).filter(item => !hasWrittenAnnotationComment(item));
            }

            function draftRecoverySummary(value) {
              const items = draftItemsFromValue(value);
              const commented = commentedDraftItems(items);
              const pinOnly = pinOnlyDraftItems(items);
              return {
                total: items.length,
                commented: commented.length,
                pinOnly: pinOnly.length,
              };
            }

            function hasCommentedDraftItems(value) {
              return draftRecoverySummary(value).commented > 0;
            }

            function currentPromptAnnotations() {
              if (!state.session) return [];
              return toolbarAnnotations()
                .filter(item => item.delivery !== 'sent')
                .filter(hasWrittenAnnotationComment);
            }
