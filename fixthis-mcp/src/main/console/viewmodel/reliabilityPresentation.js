// @requires (none)
            function reliabilityWarnings(item) {
              return item?.targetReliability?.warnings || [];
            }

            function reliabilityConfidence(item) {
              return String(item?.targetReliability?.confidence || 'unknown').toLowerCase();
            }

            function reliabilityLabel(item) {
              const confidence = reliabilityConfidence(item);
              if (confidence === 'unknown') return '';
              const warnings = reliabilityWarnings(item);
              return warnings.length ? confidence + ' · ' + countLabel(warnings.length, 'warning', 'warnings') : confidence;
            }

            function reliabilityWarningLabel(warning) {
              const value = String(warning || '').toLowerCase();
              if (value === 'possible_view_interop') return 'Possible AndroidView/WebView';
              if (value === 'no_meaningful_compose_target') return 'No Compose target';
              if (value === 'visual_area_only') return 'Visual only';
              if (value === 'low_source_candidate_margin') return 'Low source margin';
              if (value === 'source_index_stale') return 'Stale source';
              if (value === 'screen_fingerprint_mismatch_forced') return 'Forced screen mismatch';
              if (value === 'screen_fingerprint_unavailable') return 'No fingerprint';
              if (value === 'sensitive_text_redacted') return 'Redacted';
              return value.replaceAll('_', ' ');
            }
