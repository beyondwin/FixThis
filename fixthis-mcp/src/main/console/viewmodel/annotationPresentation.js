// @requires (none)
            function annotationSeverity(item) {
              return item.severity || 'med';
            }

            function annotationStatus(item) {
              return String(item.status || 'open').replace('_', '-');
            }

            function lifecyclePhase(item) {
              const status = String(item?.status || 'open').replace('-', '_');
              if (status === 'resolved') return 'resolved';
              if (status === 'wont_fix') return 'wont_fix';
              if (status === 'needs_clarification') return 'needs_clarification';
              if (status === 'in_progress') return 'in_progress';
              if (item?.delivery === 'sent') {
                return item?.staleAfterHandoff ? 'sent_modified' : 'sent';
              }
              return 'draft';
            }

            function statusLabel(item) {
              switch (lifecyclePhase(item)) {
                case 'resolved': return 'Resolved';
                case 'wont_fix': return 'Won\'t Fix';
                case 'needs_clarification': return 'Needs Clarification';
                case 'in_progress': return 'In Progress';
                case 'sent_modified': return 'Sent · Modified';
                case 'sent': return 'Sent';
                default: return 'Draft';
              }
            }

            function statusClass(item) {
              return 'st-' + lifecyclePhase(item).replace('_', '-');
            }

            function statusValueLabel(value) {
              const normalized = String(value || 'open').replace('-', '_');
              if (normalized === 'in_progress') return 'In Progress';
              if (normalized === 'needs_clarification') return 'Needs Clarification';
              if (normalized === 'wont_fix') return 'Won\'t Fix';
              if (normalized === 'resolved') return 'Resolved';
              return 'Open';
            }
